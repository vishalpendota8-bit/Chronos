package com.chronos.execution;

import com.chronos.common.PageResponse;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.security.ChronosUserDetails;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Execution history.
 *
 * <p>Two routes on two different prefixes, matching the API in the plan: history is scoped to a
 * job, while a single attempt is addressable on its own (the dashboard links to it from a
 * dead-letter entry in M5).
 */
@RestController
public class ExecutionController {

    private final ExecutionService executionService;
    private final ExecutionRetryService retryService;

    public ExecutionController(ExecutionService executionService,
                               ExecutionRetryService retryService) {
        this.executionService = executionService;
        this.retryService = retryService;
    }

    @GetMapping("/jobs/{jobId}/executions")
    public PageResponse<ExecutionResponse> listForJob(
            @PathVariable Long jobId,
            @AuthenticationPrincipal ChronosUserDetails caller,
            @PageableDefault(size = 20) Pageable pageable) {
        // Sort is fixed by the repository method (scheduled_for DESC) rather than taken from the
        // request: it matches the ix_exec_job_history index, so the query stays an index scan.
        return executionService.listForJob(jobId, caller, pageable);
    }

    @GetMapping("/executions/{id}")
    public ExecutionResponse get(@PathVariable Long id,
                                 @AuthenticationPrincipal ChronosUserDetails caller) {
        return executionService.get(id, caller);
    }

    /**
     * Queues a fresh attempt for a FAILED or DEAD execution. 202, because the attempt is queued
     * rather than run — the poller picks it up on its next tick.
     */
    @PostMapping("/executions/{id}/retry")
    public ResponseEntity<ExecutionResponse> retry(
            @PathVariable Long id,
            @AuthenticationPrincipal ChronosUserDetails caller) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(retryService.retry(id, caller));
    }
}
