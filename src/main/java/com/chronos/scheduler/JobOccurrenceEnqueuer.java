package com.chronos.scheduler;

import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Enqueues one job's current occurrence, in its own transaction.
 *
 * <p><b>Why this is a separate bean from {@link ExecutionEnqueuer} rather than a method on
 * it:</b> {@code @Transactional} works through a proxy that wraps the bean. A call from one
 * method of a bean to another method of the <em>same</em> bean goes straight to the target
 * object and never touches the proxy — so the annotation would be silently ignored and every
 * job would share the sweep's transaction. Crossing a bean boundary is what makes
 * {@code REQUIRES_NEW} real. This is one of the easiest Spring bugs to write and one of the
 * hardest to notice, because nothing fails; the transaction boundary is just not where you
 * think it is.
 */
@Service
public class JobOccurrenceEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(JobOccurrenceEnqueuer.class);

    /** A job with an attempt in any of these states is still busy. */
    private static final List<ExecutionStatus> UNFINISHED = List.of(
            ExecutionStatus.QUEUED, ExecutionStatus.RETRY_SCHEDULED, ExecutionStatus.RUNNING);

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final CronService cronService;

    public JobOccurrenceEnqueuer(JobRepository jobs, JobExecutionRepository executions,
                                 CronService cronService) {
        this.jobs = jobs;
        this.executions = executions;
        this.cronService = cronService;
    }

    /**
     * Creates the execution row for one job's current occurrence and advances the job's pointer.
     *
     * <p>{@code REQUIRES_NEW} so that one job failing cannot roll back the rest of the sweep.
     *
     * @return true if a row was inserted.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean enqueueOne(Long jobId) {
        // Re-read inside this transaction: the job may have been paused or archived since the
        // sweep selected it.
        Job job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.ENABLED || job.getNextRunAt() == null) {
            return false;
        }

        Instant occurrence = job.getNextRunAt();
        if (occurrence.isAfter(Instant.now())) {
            return false; // not due after all
        }

        /*
         * Overlap guard. If the previous occurrence is still queued, retrying or running, we do
         * NOT enqueue this one and — importantly — do NOT advance next_run_at. The occurrence is
         * therefore not lost: the next sweep tries again and it fires as soon as the previous
         * attempt finishes.
         *
         * Consequence to be aware of: a job that consistently runs longer than its period falls
         * behind rather than running several copies of itself. Whether "catch up on what was
         * missed" or "skip to the next slot" is correct is the misfire policy — M6 makes it
         * configurable. A job stuck in RUNNING blocks here until M6's reaper times it out.
         */
        if (executions.existsByJobIdAndStatusIn(jobId, UNFINISHED)) {
            log.debug("Job {} still has an unfinished execution; deferring occurrence {}",
                    jobId, occurrence);
            return false;
        }

        boolean inserted = insertOccurrence(job, occurrence);

        // Advance the pointer whether or not we won the insert race: if another node inserted
        // this occurrence first, it is enqueued either way and we must move on to the next one.
        // The job is a managed entity, so this is flushed on commit.
        job.setNextRunAt(nextAfter(job, occurrence));
        return inserted;
    }

    private boolean insertOccurrence(Job job, Instant occurrence) {
        try {
            JobExecution execution = executions.saveAndFlush(JobExecution.builder()
                    .job(job)
                    .attemptNo(1)
                    // Attempt 1 is eligible the moment its occurrence arrives; retries in M5
                    // push run_at forward while scheduled_for stays put.
                    .scheduledFor(occurrence)
                    .runAt(occurrence)
                    .status(ExecutionStatus.QUEUED)
                    .build());

            log.debug("Enqueued execution {} for job {} occurrence {}",
                    execution.getId(), job.getId(), occurrence);
            return true;

        } catch (DataIntegrityViolationException e) {
            // ux_exec_job_occurrence_attempt fired: another node enqueued this same occurrence
            // first. That is the anti-duplicate guarantee working, not an error.
            log.debug("Occurrence {} of job {} was already enqueued by another node",
                    occurrence, job.getId());
            return false;
        }
    }

    /**
     * The occurrence after the one just enqueued.
     *
     * <p>Computed from the occurrence, not from {@code now}: chaining off the schedule keeps a
     * job on its intended grid instead of letting a few seconds of processing delay accumulate
     * into visible drift over thousands of runs.
     */
    private Instant nextAfter(Job job, Instant occurrence) {
        Optional<Instant> next = cronService.nextRun(job.getCronExpr(), job.getTimezone(), occurrence);
        if (next.isEmpty()) {
            log.info("Job {} has no further occurrences after {}; it will not run again",
                    job.getId(), occurrence);
        }
        return next.orElse(null);
    }
}
