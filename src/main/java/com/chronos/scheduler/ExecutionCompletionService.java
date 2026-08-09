package com.chronos.scheduler;

import com.chronos.deadletter.DeadLetter;
import com.chronos.deadletter.DeadLetterRepository;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides what happens after an attempt finishes: done, try again later, or give up.
 *
 * <p>This is where {@link DispatchOutcome#retryable()} — computed back in M4 and until now only
 * recorded — finally drives behaviour:
 *
 * <pre>
 *   success                            → SUCCEEDED
 *   retryable, attempts remaining      → FAILED, plus a new RETRY_SCHEDULED row
 *   not retryable (4xx)                → DEAD + dead letter, immediately
 *   retryable, attempts exhausted      → DEAD + dead letter
 * </pre>
 *
 * <p>All of it happens in one transaction, so an attempt can never be marked FAILED without its
 * successor existing, and can never be marked DEAD without its dead letter.
 */
@Service
public class ExecutionCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCompletionService.class);

    private final JobExecutionRepository executions;
    private final DeadLetterRepository deadLetters;
    private final BackoffCalculator backoff;

    public ExecutionCompletionService(JobExecutionRepository executions,
                                      DeadLetterRepository deadLetters,
                                      BackoffCalculator backoff) {
        this.executions = executions;
        this.deadLetters = deadLetters;
        this.backoff = backoff;
    }

    @Transactional
    public void recordOutcome(Long executionId, DispatchOutcome outcome) {
        JobExecution execution = executions.findById(executionId).orElse(null);
        if (execution == null) {
            return;
        }

        execution.setFinishedAt(Instant.now());
        execution.setResponseCode(outcome.responseCode());
        execution.setResponseSnippet(outcome.snippet());
        execution.setErrorMessage(outcome.errorMessage());

        if (outcome.success()) {
            execution.setStatus(ExecutionStatus.SUCCEEDED);
            log.info("Execution {} succeeded with HTTP {}", executionId, outcome.responseCode());
            return;
        }

        Job job = execution.getJob();
        int attemptNo = execution.getAttemptNo();
        boolean attemptsRemain = attemptNo < job.getMaxAttempts();

        if (outcome.retryable() && attemptsRemain) {
            // This attempt is FAILED — a historical fact that stays in the timeline — and a new
            // row carries the next attempt. Keeping one row per attempt is what makes the
            // execution history readable after the fact.
            execution.setStatus(ExecutionStatus.FAILED);
            scheduleRetry(execution, job);
        } else {
            execution.setStatus(ExecutionStatus.DEAD);
            writeDeadLetter(execution, job, reasonFor(outcome, attemptNo, attemptsRemain));
        }
    }

    private void scheduleRetry(JobExecution failed, Job job) {
        Duration delay = backoff.backoffAfter(
                failed.getAttemptNo(), job.getInitialBackoffSec(), job.getBackoffMultiplier());

        Instant runAt = Instant.now().plus(delay).truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        try {
            JobExecution retry = executions.saveAndFlush(JobExecution.builder()
                    .job(job)
                    .attemptNo(failed.getAttemptNo() + 1)
                    // Same occurrence: this is another try at the same scheduled run, not a new
                    // one. That is exactly why the unique key includes attempt_no.
                    .scheduledFor(failed.getScheduledFor())
                    .runAt(runAt)
                    .status(ExecutionStatus.RETRY_SCHEDULED)
                    .build());

            log.info("Execution {} failed (attempt {}/{}); retry {} scheduled in {}s",
                    failed.getId(), failed.getAttemptNo(), job.getMaxAttempts(),
                    retry.getId(), delay.toSeconds());

        } catch (DataIntegrityViolationException e) {
            // Another node already scheduled this attempt — possible if the same execution was
            // dispatched twice after a reaper timeout. The unique index makes that harmless.
            log.warn("Retry attempt {} for occurrence {} of job {} already exists",
                    failed.getAttemptNo() + 1, failed.getScheduledFor(), job.getId());
        }
    }

    private void writeDeadLetter(JobExecution execution, Job job, String reason) {
        try {
            deadLetters.saveAndFlush(DeadLetter.builder()
                    .execution(execution)
                    .job(job)
                    .reason(reason)
                    .failedAt(Instant.now())
                    .build());

            log.warn("Execution {} of job {} is DEAD: {}", execution.getId(), job.getId(), reason);

        } catch (DataIntegrityViolationException e) {
            // ux_dead_letters_execution: one execution can only die once.
            log.debug("Dead letter for execution {} already exists", execution.getId());
        }
    }

    /** A short, human-readable explanation — this is what an operator sees in the dashboard. */
    private String reasonFor(DispatchOutcome outcome, int attemptNo, boolean attemptsRemain) {
        String detail = outcome.errorMessage() == null ? "unknown error" : outcome.errorMessage();

        if (!outcome.retryable()) {
            // Note it says "not retried", not "gave up": a 4xx means the request itself is
            // wrong, so further identical attempts would fail identically.
            return detail + " — not retried (client error)";
        }
        if (!attemptsRemain) {
            return detail + " — gave up after " + attemptNo + " attempt(s)";
        }
        return detail;
    }
}
