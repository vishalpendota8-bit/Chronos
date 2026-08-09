package com.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code chronos.scheduler.*} — the knobs on the execution engine.
 *
 * @param enabled master switch for the two @Scheduled loops. Turned off in integration tests so
 *        they can drive the enqueuer and poller by hand and assert on a known state instead of
 *        racing a background timer.
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
        Dispatch dispatch
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
}
