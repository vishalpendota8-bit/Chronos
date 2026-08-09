package com.chronos.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * One claim-and-dispatch cycle.
 *
 * <p>Kept separate from {@link SchedulerPoller} — which owns the {@code @Scheduled} timers and
 * can be switched off — so that the logic is always available to be driven directly. That is
 * what lets integration tests step the engine deterministically instead of racing a timer.
 */
@Service
public class ExecutionPoller {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPoller.class);

    private final ExecutionClaimService claimService;
    private final ExecutionRunner runner;
    private final ThreadPoolTaskExecutor dispatchExecutor;
    private final InFlightRegistry registry;

    public ExecutionPoller(ExecutionClaimService claimService,
                           ExecutionRunner runner,
                           @Qualifier("dispatchExecutor") ThreadPoolTaskExecutor dispatchExecutor,
                           InFlightRegistry registry) {
        this.claimService = claimService;
        this.runner = runner;
        this.dispatchExecutor = dispatchExecutor;
        this.registry = registry;
    }

    /**
     * Claims due executions and submits each to the dispatch pool.
     *
     * @return how many executions were submitted.
     */
    public int pollOnce() {
        // Checked before claiming, not after. A node that is shutting down must not take on work
        // it will not be around to finish — and this check is what makes that free rather than
        // something shutdown has to clean up afterwards.
        if (!registry.isAccepting()) {
            return 0;
        }

        // claimBatch() commits before returning. Submitting only after that commit is essential:
        // a worker thread that read the row before RUNNING was visible would see a QUEUED row
        // and decline to run it.
        List<Long> claimed = claimService.claimBatch();

        int submitted = 0;
        for (Long executionId : claimed) {
            // Registered before submission: see InFlightRegistry#register for why the order
            // matters to a shutdown landing in the middle of this loop.
            registry.register(executionId);
            try {
                dispatchExecutor.execute(() -> {
                    try {
                        runner.run(executionId);
                    } finally {
                        // finally, not after the call: runner.run() is written never to throw,
                        // but if that ever stops being true a leaked entry would make every
                        // subsequent shutdown wait out its full drain timeout.
                        registry.complete(executionId);
                    }
                });
                submitted++;
            } catch (RejectedExecutionException e) {
                // The bounded queue is full. Hand the row back so an idle node can take it,
                // rather than leaving it RUNNING where no poller would ever look at it again.
                registry.complete(executionId);
                claimService.release(executionId);
            }
        }

        if (submitted > 0) {
            log.debug("Dispatched {} execution(s)", submitted);
        }
        return submitted;
    }
}
