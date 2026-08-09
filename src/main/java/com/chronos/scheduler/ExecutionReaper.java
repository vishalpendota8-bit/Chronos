package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The reaper: the sweep that rescues executions stuck in RUNNING.
 *
 * <p><b>Why the engine needs one at all.</b> Everything up to M5 assumes a dispatch eventually
 * writes its own result. A process that dies mid-dispatch breaks that assumption in a way no
 * amount of care inside the process can fix — the claim is committed, the row says RUNNING, and
 * the only thread that was going to finish it no longer exists. Nothing else will ever touch
 * that row, because RUNNING is precisely the status the claim query ignores. Two things then
 * go wrong at once:
 *
 * <ul>
 *   <li>that occurrence never completes, and never retries;</li>
 *   <li>worse, the overlap guard in {@link JobOccurrenceEnqueuer} reads the unfinished row as
 *       "this job is busy", so the job never gets scheduled again either. One crashed node
 *       silently stops a job forever.</li>
 * </ul>
 *
 * <p>This is the general shape of every claim-based queue: whatever can claim work must have a
 * way to un-claim work whose owner disappeared. Chronos does it with a timeout rather than with
 * node heartbeats or leases, because the timeout is information we already have — every job
 * declares its own {@code timeout_sec}.
 *
 * <p><b>The unavoidable tradeoff: this can double-run a job.</b> The reaper cannot distinguish
 * "the node is dead" from "the node is alive but slow", so a dispatch that overruns its timeout
 * plus the grace period is reaped and retried while the original request may still be in flight
 * at the target. That is why every dispatch carries {@code X-Idempotency-Key} (the execution id,
 * see {@link HttpDispatcher}) — at-least-once delivery is the guarantee, and the target is
 * expected to de-duplicate. Exactly-once would need the target to participate in a transaction
 * with us, which is not something an arbitrary HTTP endpoint can do.
 *
 * <p>Two guards keep the damage bounded when that race does happen: the reaper's retry insert is
 * protected by the {@code (job_id, scheduled_for, attempt_no)} unique index, and
 * {@link ExecutionCompletionService#recordOutcome} ignores a result for a row that is no longer
 * RUNNING — so the late thread's answer is discarded rather than overwriting the reaped outcome.
 */
@Service
public class ExecutionReaper {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReaper.class);

    private final JobExecutionRepository executions;
    private final ExecutionCompletionService completion;
    private final SchedulerProperties properties;

    public ExecutionReaper(JobExecutionRepository executions,
                           ExecutionCompletionService completion,
                           SchedulerProperties properties) {
        this.executions = executions;
        this.completion = completion;
        this.properties = properties;
    }

    /**
     * One reap sweep.
     *
     * <p><b>Why this whole method is one transaction</b>, unlike the poller's claim-then-release:
     * there is no slow work in it. The rows are selected with {@code FOR UPDATE OF e SKIP LOCKED},
     * failed, and committed — all database writes, milliseconds. Recording the outcome inside the
     * same transaction that holds the locks is what makes reaping atomic: a row cannot be seen as
     * reaped-but-not-retried, and a second node cannot reap the same row concurrently.
     *
     * <p>{@code recordOutcome} is called on another bean on purpose. It is {@code @Transactional}
     * with the default REQUIRED propagation, so it <em>joins</em> this transaction rather than
     * starting its own — and going through the proxy is what makes that participation happen at
     * all. The reaper therefore reuses the entire M5 decision tree (retry with backoff, or dead
     * letter when the attempts are spent) instead of restating it.
     *
     * @return how many executions were reaped.
     */
    @Transactional
    public int reapOnce() {
        SchedulerProperties.Reap reap = properties.reap();
        Instant now = Instant.now();

        List<JobExecution> stuck = executions.claimTimedOut(now, reap.graceSec(), reap.batchSize());
        if (stuck.isEmpty()) {
            return 0;
        }

        for (JobExecution execution : stuck) {
            completion.recordOutcome(execution.getId(), DispatchOutcome.retryableFailure(
                    null, null, describe(execution, now)));
        }

        log.warn("Reaped {} execution(s) stuck in RUNNING", stuck.size());
        return stuck.size();
    }

    /**
     * The message an operator reads in the execution history and the dead-letter queue, so it
     * has to say what actually happened rather than just "timeout".
     *
     * <p>The two cases really are different failures. A row with {@code started_at} set was
     * dispatched and the call never came back — the target is the suspect. A row with only
     * {@code claimed_at} was never dispatched at all: this node (or a node now gone) claimed it
     * and died, or the JVM stalled, before the request was even made. Blaming the target for
     * the second case would send someone debugging the wrong system.
     *
     * <p>Marked retryable in {@link #reapOnce} either way, which is the right call for both: a
     * timeout may succeed on the next attempt, and work that was never dispatched has not been
     * tried even once.
     */
    private String describe(JobExecution execution, Instant now) {
        if (execution.getStartedAt() == null) {
            long heldFor = secondsSince(execution.getClaimedAt(), now);
            return "Reaped: claimed " + heldFor + "s ago but never dispatched"
                    + " (the node that claimed it is gone)";
        }

        long ranFor = secondsSince(execution.getStartedAt(), now);
        return "Reaped: no response " + ranFor + "s after dispatch, exceeding the job's timeout of "
                + execution.getJob().getTimeoutSec() + "s";
    }

    /** Null-tolerant: claimed_at is nullable in the schema, even though a RUNNING row always has one. */
    private long secondsSince(Instant from, Instant now) {
        return from == null ? -1 : Duration.between(from, now).getSeconds();
    }
}
