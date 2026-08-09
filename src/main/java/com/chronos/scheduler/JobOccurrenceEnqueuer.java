package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import com.chronos.job.MisfirePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
 *
 * <p><b>M6 added the misfire decision here</b> — see {@link #enqueueOne}. It belongs at this
 * exact point because this is the only place that knows both when an occurrence was supposed to
 * happen and when we actually got round to it.
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
    private final SchedulerProperties properties;

    public JobOccurrenceEnqueuer(JobRepository jobs, JobExecutionRepository executions,
                                 CronService cronService, SchedulerProperties properties) {
        this.jobs = jobs;
        this.executions = executions;
        this.cronService = cronService;
        this.properties = properties;
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

        Instant now = Instant.now();
        Instant occurrence = job.getNextRunAt();
        if (occurrence.isAfter(now)) {
            return false; // not due after all
        }

        /*
         * Overlap guard. If the previous occurrence is still queued, retrying or running, we do
         * NOT enqueue this one and — importantly — do NOT advance next_run_at. The occurrence is
         * therefore not lost: the next sweep tries again and it fires as soon as the previous
         * attempt finishes.
         *
         * Consequence: a job that consistently runs longer than its period falls behind rather
         * than running several copies of itself. It keeps falling behind until the deferred
         * occurrence crosses the misfire threshold, at which point the policy below decides
         * whether to keep running late (FIRE_NOW) or to abandon the backlog and rejoin the
         * schedule (SKIP). A job stuck in RUNNING is unblocked by ExecutionReaper.
         */
        if (executions.existsByJobIdAndStatusIn(jobId, UNFINISHED)) {
            log.debug("Job {} still has an unfinished execution; deferring occurrence {}",
                    jobId, occurrence);
            return false;
        }

        boolean misfire = isMisfire(occurrence, now);
        if (misfire && job.getMisfirePolicy() == MisfirePolicy.SKIP) {
            return skipMisfire(job, occurrence, now);
        }

        boolean inserted = insertOccurrence(job, occurrence);

        // Advance the pointer whether or not we won the insert race: if another node inserted
        // this occurrence first, it is enqueued either way and we must move on to the next one.
        // The job is a managed entity, so this is flushed on commit.
        job.setNextRunAt(misfire ? fireNowResume(job, occurrence, now) : nextAfter(job, occurrence));
        return inserted;
    }

    // ------------------------------------------------------------------ misfire

    /**
     * Was this occurrence discovered so late that it counts as a misfire?
     *
     * <p>The threshold exists so that ordinary latency is not mistaken for one. The sweep only
     * runs every {@code enqueue-interval-ms}, so every occurrence is a few seconds late by
     * construction; a misfire means late by minutes, which only happens when nobody was sweeping
     * or the job was blocked behind its own previous run.
     */
    private boolean isMisfire(Instant occurrence, Instant now) {
        return Duration.between(occurrence, now).getSeconds() > properties.misfireThresholdSec();
    }

    /**
     * FIRE_NOW: the late occurrence has just been enqueued; now rejoin the present.
     *
     * <p><b>This is the subtle half of FIRE_NOW, and without it the policy would mean something
     * quite different.</b> The normal path chains the pointer off the occurrence
     * ({@link #nextAfter}) to avoid drift. Do that after a long outage and the new pointer is
     * still in the past, so the next sweep enqueues that one too, and the one after it — the job
     * silently replays its entire backlog one run at a time, each blocked behind the last by the
     * overlap guard. A minutely job down for a day would spend hours firing yesterday's runs
     * before it caught up to today.
     *
     * <p>So FIRE_NOW means <em>one</em> catch-up run, not all of them: the missed occurrence is
     * executed because something did need to happen, and the pointer then jumps to the next
     * occurrence after now. Anything further back is gone, exactly as with SKIP. The two
     * policies differ only in whether that single catch-up run happens.
     */
    private Instant fireNowResume(Job job, Instant occurrence, Instant now) {
        Instant resumeAt = cronService.nextRun(job.getCronExpr(), job.getTimezone(), now)
                .orElse(null);

        log.warn("Job {} misfired: occurrence {} was {}s late and the policy is FIRE_NOW; "
                        + "running it once and resuming at {}",
                job.getId(), occurrence, Duration.between(occurrence, now).getSeconds(), resumeAt);
        return resumeAt;
    }

    /**
     * SKIP: abandon the late occurrence and rejoin the schedule.
     *
     * <p>The pointer is recomputed from <em>now</em>, not from the occurrence. That matters when
     * many occurrences were missed: stepping to "the one after the missed one" would land on
     * another missed one, and the next sweep would skip that, and so on — a minutely job idle
     * for a day would take 1,440 sweeps to catch up to the present. Asking for the next
     * occurrence after now collapses the whole backlog in a single step, which is exactly what
     * "skip what was missed" should mean.
     *
     * @return false — nothing was enqueued, which is the point.
     */
    private boolean skipMisfire(Job job, Instant occurrence, Instant now) {
        Instant resumeAt = cronService.nextRun(job.getCronExpr(), job.getTimezone(), now)
                .orElse(null);

        job.setNextRunAt(resumeAt);

        log.warn("Job {} misfired: occurrence {} was {}s late and the policy is SKIP; "
                        + "skipping it and resuming at {}",
                job.getId(), occurrence, Duration.between(occurrence, now).getSeconds(), resumeAt);
        return false;
    }

    // ------------------------------------------------------------------ insert

    private boolean insertOccurrence(Job job, Instant occurrence) {
        try {
            JobExecution execution = executions.saveAndFlush(JobExecution.builder()
                    .job(job)
                    .attemptNo(1)
                    // Attempt 1 is eligible the moment its occurrence arrives; retries push
                    // run_at forward while scheduled_for stays put.
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
     * into visible drift over thousands of runs. (SKIP is the deliberate exception — see
     * {@link #skipMisfire}, which is the one case where rejoining the present is the goal.)
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
