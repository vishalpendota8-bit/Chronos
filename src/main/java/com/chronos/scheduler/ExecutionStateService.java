package com.chronos.scheduler;

import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The transaction that opens a dispatch. Its counterpart is
 * {@link ExecutionCompletionService#recordOutcome}, which closes it.
 *
 * <p>Separate from {@link ExecutionRunner} for the same proxy reason spelled out on
 * {@link JobOccurrenceEnqueuer}: {@code @Transactional} is applied by a proxy around the bean,
 * so a self-call from {@code ExecutionRunner.run} into its own transactional method would run
 * with no transaction at all. Crossing a bean boundary is what makes the annotation take effect.
 */
@Service
public class ExecutionStateService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStateService.class);

    private final JobExecutionRepository executions;

    public ExecutionStateService(JobExecutionRepository executions) {
        this.executions = executions;
    }

    /**
     * Stamps {@code started_at} and reads everything the HTTP call needs.
     *
     * <p>Two reasons this is its own step. First, {@code started_at} is what M6's reaper measures
     * a timeout against, so it must be persisted <em>before</em> the call — and being distinct
     * from {@code claimed_at}, the gap between the two shows how long the row waited for a free
     * thread. Second, {@code job} is a lazy association: it can only be read while a transaction
     * and its persistence context are open, so everything needed is copied into a plain record
     * here rather than handing the entity to another thread.
     *
     * @return null if the row disappeared or is no longer RUNNING.
     */
    @Transactional
    public HttpDispatcher.DispatchRequest startAndDescribe(Long executionId) {
        JobExecution execution = executions.findById(executionId).orElse(null);
        if (execution == null) {
            log.warn("Execution {} disappeared before dispatch", executionId);
            return null;
        }
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            log.debug("Execution {} is {} rather than RUNNING; skipping dispatch",
                    executionId, execution.getStatus());
            return null;
        }

        execution.setStartedAt(Instant.now());

        Job job = execution.getJob(); // initialises the lazy proxy inside this transaction
        return new HttpDispatcher.DispatchRequest(
                execution.getId(),
                job.getTargetUrl(),
                job.getHttpMethod(),
                job.getHeaders(),
                job.getPayload(),
                job.getTimeoutSec());
    }

}
