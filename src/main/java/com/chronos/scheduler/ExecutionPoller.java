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

    public ExecutionPoller(ExecutionClaimService claimService,
                           ExecutionRunner runner,
                           @Qualifier("dispatchExecutor") ThreadPoolTaskExecutor dispatchExecutor) {
        this.claimService = claimService;
        this.runner = runner;
        this.dispatchExecutor = dispatchExecutor;
    }

    /**
     * Claims due executions and submits each to the dispatch pool.
     *
     * @return how many executions were submitted.
     */
    public int pollOnce() {
        // claimBatch() commits before returning. Submitting only after that commit is essential:
        // a worker thread that read the row before RUNNING was visible would see a QUEUED row
        // and decline to run it.
        List<Long> claimed = claimService.claimBatch();

        int submitted = 0;
        for (Long executionId : claimed) {
            try {
                dispatchExecutor.execute(() -> runner.run(executionId));
                submitted++;
            } catch (RejectedExecutionException e) {
                // The bounded queue is full. Hand the row back so an idle node can take it,
                // rather than leaving it RUNNING where no poller would ever look at it again.
                claimService.release(executionId);
            }
        }

        if (submitted > 0) {
            log.debug("Dispatched {} execution(s)", submitted);
        }
        return submitted;
    }
}
