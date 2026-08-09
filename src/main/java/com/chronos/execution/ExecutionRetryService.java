package com.chronos.execution;

import com.chronos.common.BadRequestException;
import com.chronos.common.NotFoundException;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.job.Job;
import com.chronos.job.JobStatus;
import com.chronos.security.ChronosUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Manual re-run of a finished attempt, behind both {@code POST /executions/{id}/retry} and
 * dead-letter replay.
 *
 * <p><b>What a manual retry is:</b> a new attempt row for the <em>same occurrence</em>, eligible
 * immediately. It is not a new cron occurrence — the schedule is untouched — so the history for
 * that occurrence reads as one continuous story of attempts.
 */
@Service
public class ExecutionRetryService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRetryService.class);

    private final JobExecutionRepository executions;

    public ExecutionRetryService(JobExecutionRepository executions) {
        this.executions = executions;
    }

    /**
     * Queues a fresh attempt for a failed execution.
     *
     * <p><b>Tradeoff — a manual retry deliberately ignores {@code max_attempts}.</b> An operator
     * asking for a retry has usually just fixed whatever was broken, and refusing because the
     * automatic budget is spent would be unhelpful. The consequence is that the new attempt
     * number is already past the limit, so if it fails <em>and</em> the failure is retryable, the
     * normal policy sees no attempts remaining and dead-letters it again. In effect a manual
     * retry buys exactly one more try, which is the behaviour an operator expects from a button
     * labelled "retry".
     */
    @Transactional
    public ExecutionResponse retry(Long executionId, ChronosUserDetails caller) {
        JobExecution failed = executions.findById(executionId)
                .orElseThrow(() -> NotFoundException.of("Execution", executionId));

        Job job = failed.getJob();
        if (!caller.isAdmin() && !job.getOwner().getId().equals(caller.id())) {
            // 404 rather than 403 — see NotFoundException; an id must never be confirmed.
            throw NotFoundException.of("Execution", executionId);
        }

        // Only a finished-and-unsuccessful attempt can be retried. Retrying something already
        // queued or running would create a second live attempt for one occurrence, which is
        // exactly what the rest of the engine works to prevent.
        if (failed.getStatus() != ExecutionStatus.FAILED && failed.getStatus() != ExecutionStatus.DEAD) {
            throw new BadRequestException(
                    "Execution " + executionId + " is " + failed.getStatus()
                            + "; only FAILED or DEAD executions can be retried");
        }

        if (job.getStatus() == JobStatus.ARCHIVED) {
            throw new BadRequestException("Job " + job.getId() + " is archived and cannot be retried");
        }

        if (executions.existsByJobIdAndStatusIn(job.getId(), ExecutionStatus.CLAIMABLE)) {
            throw new BadRequestException(
                    "Job " + job.getId() + " already has an attempt waiting to run");
        }

        // Not failed.getAttemptNo() + 1: an automatic retry may already hold that number for
        // this occurrence, and (job_id, scheduled_for, attempt_no) is unique.
        Integer highest = executions.findMaxAttemptNo(job.getId(), failed.getScheduledFor());
        int nextAttempt = (highest == null ? failed.getAttemptNo() : highest) + 1;

        JobExecution retry = executions.saveAndFlush(JobExecution.builder()
                .job(job)
                .attemptNo(nextAttempt)
                .scheduledFor(failed.getScheduledFor())
                // Eligible now — a manual retry is an explicit "run it again", so no backoff.
                .runAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .status(ExecutionStatus.QUEUED)
                .build());

        log.info("User {} queued manual retry {} (attempt {}) for execution {} of job {}",
                caller.id(), retry.getId(), nextAttempt, executionId, job.getId());

        return ExecutionResponse.from(retry);
    }
}
