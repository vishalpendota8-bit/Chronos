package com.chronos.execution;

import com.chronos.common.NotFoundException;
import com.chronos.common.PageResponse;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.security.ChronosUserDetails;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to execution history.
 *
 * <p><b>Ownership:</b> an execution has no owner of its own — it inherits the ownership of its
 * job. Every lookup here therefore resolves the job first and applies the same rule as
 * {@code JobService}: a USER may only see their own, an ADMIN may see everything, and anything
 * else is reported as 404 rather than 403 so an id is never confirmed to exist.
 */
@Service
public class ExecutionService {

    private final JobExecutionRepository executions;
    private final JobRepository jobs;

    public ExecutionService(JobExecutionRepository executions, JobRepository jobs) {
        this.executions = executions;
        this.jobs = jobs;
    }

    /** History for one job, newest occurrence first. */
    @Transactional(readOnly = true)
    public PageResponse<ExecutionResponse> listForJob(Long jobId, ChronosUserDetails caller,
                                                      Pageable pageable) {
        requireVisibleJob(jobId, caller);
        return PageResponse.from(
                executions.findByJobIdOrderByScheduledForDesc(jobId, pageable),
                ExecutionResponse::from);
    }

    @Transactional(readOnly = true)
    public ExecutionResponse get(Long executionId, ChronosUserDetails caller) {
        JobExecution execution = executions.findById(executionId)
                .orElseThrow(() -> NotFoundException.of("Execution", executionId));

        // The ownership check happens against the parent job. Note this runs inside the
        // transaction, so touching the lazy job proxy is safe here.
        if (!caller.isAdmin() && !execution.getJob().getOwner().getId().equals(caller.id())) {
            throw NotFoundException.of("Execution", executionId);
        }

        return ExecutionResponse.from(execution);
    }

    private Job requireVisibleJob(Long jobId, ChronosUserDetails caller) {
        return (caller.isAdmin()
                ? jobs.findById(jobId)
                : jobs.findByIdAndOwnerId(jobId, caller.id()))
                .orElseThrow(() -> NotFoundException.of("Job", jobId));
    }
}
