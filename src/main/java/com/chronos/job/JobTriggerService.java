package com.chronos.job;

import com.chronos.common.BadRequestException;
import com.chronos.common.ConflictException;
import com.chronos.common.NotFoundException;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.security.ChronosUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * "Run it now" — an out-of-band occurrence, requested by a person.
 *
 * <p><b>What this is not:</b> it is not a rescheduling. {@code next_run_at} is deliberately left
 * exactly where it was, so triggering a job at 14:32 does not move its 02:00 nightly run. The
 * manual run and the schedule are independent, which is what makes this safe to press while
 * debugging.
 *
 * <p><b>How it differs from {@code POST /executions/{id}/retry}.</b> A retry is another attempt
 * at an occurrence that already exists, sharing its {@code scheduled_for} so the history for
 * that occurrence stays one story. A trigger invents a <em>new</em> occurrence, stamped now,
 * with no prior attempt behind it. Both end up as ordinary rows the poller claims — the engine
 * needs no notion of "manual", which is exactly why manual runs get the same retries, backoff
 * and dead-lettering as scheduled ones for free.
 *
 * <p><b>Why this bean lives in the job package</b> rather than beside the retry service in
 * {@code execution}: the resource being acted on is the job, and the rules it enforces
 * (ownership, archived, not already busy) are all job rules. That it happens to write an
 * execution row is an implementation detail of what "run" means.
 */
@Service
public class JobTriggerService {

    private static final Logger log = LoggerFactory.getLogger(JobTriggerService.class);

    private static final List<ExecutionStatus> RUNNING_ONLY = List.of(ExecutionStatus.RUNNING);

    private final JobRepository jobs;
    private final JobExecutionRepository executions;

    public JobTriggerService(JobRepository jobs, JobExecutionRepository executions) {
        this.jobs = jobs;
        this.executions = executions;
    }

    /**
     * Queues one immediate execution for a job.
     *
     * <p><b>PAUSED jobs may be triggered, ARCHIVED ones may not.</b> The distinction is what each
     * state means. Pausing suspends the <em>schedule</em>; an operator pausing a job and then
     * triggering it by hand is the normal way to test a fix without letting the cron loose, and
     * refusing would make pause less useful than it should be. ARCHIVED is a tombstone — the job
     * is history, and history does not run.
     */
    @Transactional
    public ExecutionResponse trigger(Long jobId, ChronosUserDetails caller) {
        Job job = loadForCaller(jobId, caller);

        if (job.getStatus() == JobStatus.ARCHIVED) {
            throw new BadRequestException("Job " + jobId + " is archived and cannot be triggered");
        }

        // The same overlap rule the scheduler enforces, applied here for the same reason: two
        // live attempts for one job is the state the whole engine is built to avoid, and a
        // trigger button is no reason to make an exception. 409 rather than 400 — the request is
        // well-formed, the resource is simply busy, and the caller can usefully retry later.
        if (executions.existsByJobIdAndStatusIn(jobId, ExecutionStatus.CLAIMABLE)) {
            throw new ConflictException("Job " + jobId + " already has an attempt waiting to run");
        }
        if (executions.existsByJobIdAndStatusIn(jobId, RUNNING_ONLY)) {
            // Split from the check above so the message says which of the two it is; "waiting to
            // run" and "running right now" send an operator to different places.
            throw new ConflictException("Job " + jobId + " is already running");
        }

        // Truncated to microseconds to match the column's precision — see CronService. This is
        // also the occurrence key, and a value that changed when read back would break the
        // (job_id, scheduled_for, attempt_no) uniqueness this row depends on.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        try {
            JobExecution execution = executions.saveAndFlush(JobExecution.builder()
                    .job(job)
                    .attemptNo(1)
                    // A brand-new occurrence of its own, not a retry of an existing one: it is
                    // scheduled for now, and eligible now.
                    .scheduledFor(now)
                    .runAt(now)
                    .status(ExecutionStatus.QUEUED)
                    .build());

            log.info("User {} triggered job {} manually as execution {}",
                    caller.id(), jobId, execution.getId());

            return ExecutionResponse.from(execution);

        } catch (DataIntegrityViolationException e) {
            // Only reachable if two triggers land in the same microsecond and collide on the
            // occurrence key. Vanishingly unlikely, but the alternative to handling it is a 500.
            throw new ConflictException("Job " + jobId + " was triggered concurrently; try again");
        }
    }

    /** The same ownership gate as {@code JobService}: scoped lookup, and 404 rather than 403. */
    private Job loadForCaller(Long id, ChronosUserDetails caller) {
        return (caller.isAdmin()
                ? jobs.findById(id)
                : jobs.findByIdAndOwnerId(id, caller.id()))
                .orElseThrow(() -> NotFoundException.of("Job", id));
    }
}
