package com.chronos.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one claimed execution: stamp it started, call the target, record what happened.
 *
 * <p>This is what executes on a {@code chronos-dispatch-*} thread. Note the shape — two short
 * transactions with the slow part <em>between</em> them, never inside one:
 *
 * <pre>
 *   [tx] load the request + stamp started_at
 *        → HTTP call, up to timeout_sec, no transaction, no locks held
 *   [tx] write the result
 * </pre>
 *
 * <p>Holding a transaction across the HTTP call is the classic way to exhaust a connection pool:
 * 16 dispatch threads each waiting up to 300 seconds would pin 16 of the 20 available
 * connections doing nothing at all.
 */
@Service
public class ExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRunner.class);

    private final ExecutionStateService state;
    private final ExecutionCompletionService completion;
    private final HttpDispatcher dispatcher;

    public ExecutionRunner(ExecutionStateService state, ExecutionCompletionService completion,
                           HttpDispatcher dispatcher) {
        this.state = state;
        this.completion = completion;
        this.dispatcher = dispatcher;
    }

    /** Entry point for the dispatch pool. Never throws — a dead worker thread helps nobody. */
    public void run(Long executionId) {
        try {
            HttpDispatcher.DispatchRequest request = state.startAndDescribe(executionId);
            if (request == null) {
                return; // vanished, or no longer RUNNING
            }

            completion.recordOutcome(executionId, dispatcher.dispatch(request));

        } catch (Exception e) {
            log.error("Unexpected error running execution {}", executionId, e);
            // Best effort: do not let a bug on our side leave the row RUNNING forever, because
            // a RUNNING row is invisible to every poller until M6's reaper exists. Marked
            // retryable so a transient bug does not permanently kill the occurrence.
            try {
                completion.recordOutcome(executionId, DispatchOutcome.retryableFailure(
                        null, null, "Internal dispatch error: " + e.getClass().getSimpleName()));
            } catch (Exception nested) {
                log.error("Could not record failure for execution {}", executionId, nested);
            }
        }
    }
}
