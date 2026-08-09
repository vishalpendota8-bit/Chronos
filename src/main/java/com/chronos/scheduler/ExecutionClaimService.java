package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Claims due executions for this node, and hands them back after a rejected dispatch.
 *
 * <p><b>The critical property of {@link #claimBatch}:</b> it is short. It selects with
 * {@code FOR UPDATE SKIP LOCKED}, flips the rows to RUNNING, and commits. No HTTP call, no
 * waiting on anything external. Row locks last milliseconds, and the database connection goes
 * straight back to the pool.
 *
 * <p>Two things stop a second node from taking the same work, and they cover different windows:
 * the <em>row lock</em> covers the instant between SELECT and COMMIT, and the <em>RUNNING
 * status</em> covers everything after the commit, because the claiming query only ever looks at
 * QUEUED and RETRY_SCHEDULED rows.
 */
@Service
public class ExecutionClaimService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionClaimService.class);

    private final JobExecutionRepository executions;
    private final SchedulerProperties properties;

    public ExecutionClaimService(JobExecutionRepository executions, SchedulerProperties properties) {
        this.executions = executions;
        this.properties = properties;
    }

    /**
     * Claims up to {@code batchSize} due executions and marks them RUNNING.
     *
     * @return the ids of the claimed rows. Ids, not entities, on purpose: the caller hands these
     *         to another thread, and a JPA entity belongs to the (now closed) persistence context
     *         that loaded it. Passing an id across a thread boundary is always safe; passing a
     *         detached entity with a lazy {@code job} proxy is how you get a
     *         LazyInitializationException on a worker thread.
     */
    @Transactional
    public List<Long> claimBatch() {
        List<JobExecution> claimed = executions.claimDue(Instant.now(), properties.batchSize());
        if (claimed.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        for (JobExecution execution : claimed) {
            execution.setStatus(ExecutionStatus.RUNNING);
            execution.setClaimedAt(now);
        }
        // Managed entities: the UPDATEs flush on commit, which is also when the locks release.

        log.debug("Claimed {} execution(s)", claimed.size());
        return claimed.stream().map(JobExecution::getId).toList();
    }

    /**
     * Puts a claimed execution back, used when the dispatch pool rejects the task.
     *
     * <p>Returning it to QUEUED rather than leaving it RUNNING matters: a RUNNING row is
     * invisible to every poller on every node, so it would sit untouched until M6's reaper
     * noticed it. Released immediately, an idle node can pick it up on its very next tick.
     */
    @Transactional
    public void release(Long executionId) {
        executions.findById(executionId).ifPresent(execution -> {
            if (execution.getStatus() != ExecutionStatus.RUNNING) {
                return; // already progressed; nothing to undo
            }
            // Back to the state it was claimed from. attempt_no is untouched, so this is not a
            // retry — it is the same attempt, simply not started yet.
            execution.setStatus(execution.getAttemptNo() > 1
                    ? ExecutionStatus.RETRY_SCHEDULED
                    : ExecutionStatus.QUEUED);
            execution.setClaimedAt(null);
            log.warn("Released execution {} back to the queue (dispatch pool saturated)", executionId);
        });
    }
}
