package com.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code chronos.scheduler.*} — the knobs on the execution engine.
 *
 * @param enabled master switch for the @Scheduled loops. Turned off in integration tests so
 *        they can drive the enqueuer, poller and reaper by hand and assert on a known state
 *        instead of racing a background timer.
 * @param enqueueIntervalMs how often jobs whose {@code next_run_at} has arrived are turned into
 *        execution rows.
 * @param pollIntervalMs how often due execution rows are claimed and dispatched.
 * @param batchSize maximum rows claimed per poll. Bounds how much work one node grabs at once,
 *        which is what lets a second node get a share (see the SKIP LOCKED note in
 *        {@link com.chronos.execution.JobExecutionRepository}).
 * @param enqueueBatchSize maximum jobs converted into executions per enqueue tick.
 * @param connectTimeoutSec TCP connect timeout, shared by all dispatches. Separate from the
 *        per-job read timeout: failing to connect is a different problem from a slow response.
 * @param responseSnippetLimit how many characters of the response body to keep for debugging.
 * @param misfireThresholdSec how late an occurrence must be before it counts as a misfire and
 *        the job's {@link com.chronos.job.MisfirePolicy} is consulted. Must comfortably exceed
 *        {@code enqueueIntervalMs}, or ordinary sweep latency would be reported as a misfire.
 * @param reap the timeout reaper's settings.
 * @param shutdownDrainSec how long a shutting-down node waits for its in-flight dispatches
 *        before releasing whatever is left back to the queue. See
 *        {@link com.chronos.scheduler.SchedulerLifecycle}.
 */
@ConfigurationProperties(prefix = "chronos.scheduler")
public record SchedulerProperties(
        boolean enabled,
        long enqueueIntervalMs,
        long pollIntervalMs,
        int batchSize,
        int enqueueBatchSize,
        int connectTimeoutSec,
        int responseSnippetLimit,
        long misfireThresholdSec,
        int shutdownDrainSec,
        Dispatch dispatch,
        Reap reap
) {
    /**
     * The bounded worker pool that performs the HTTP calls.
     *
     * @param corePoolSize threads kept alive permanently.
     * @param maxPoolSize hard ceiling on concurrent dispatches from one node.
     * @param queueCapacity how many claimed executions may wait for a thread. Bounded on
     *        purpose — an unbounded queue would let a node claim work it cannot get to, and
     *        those rows would sit RUNNING while another idle node was forbidden from taking them.
     */
    public record Dispatch(int corePoolSize, int maxPoolSize, int queueCapacity) {
    }

    /**
     * The reaper: the sweep that finds executions stuck in RUNNING and fails them.
     *
     * @param intervalMs how often to look. There is no hurry — a stuck row is already late — and
     *        a slower sweep keeps the query off the hot path, so this runs far less often than
     *        the poller.
     * @param graceSec added to each job's own {@code timeout_sec} before a row is considered
     *        abandoned. The HTTP client already enforces {@code timeout_sec}, so a healthy
     *        dispatch always finishes within it; the grace covers the gap between the request
     *        timing out and the result being written — GC pauses, a slow commit, a busy pool.
     *        Too small and the reaper races live dispatches and double-runs jobs; too large and
     *        a crashed node's work sits idle for that much longer. See
     *        {@link com.chronos.scheduler.ExecutionReaper}.
     * @param batchSize maximum rows reaped per sweep, bounded for the same reason as the claim
     *        batch.
     */
    public record Reap(long intervalMs, int graceSec, int batchSize) {
    }
}
