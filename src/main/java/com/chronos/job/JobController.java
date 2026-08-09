package com.chronos.job;

import com.chronos.common.PageResponse;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.job.dto.CronPreviewRequest;
import com.chronos.job.dto.CronPreviewResponse;
import com.chronos.job.dto.JobRequest;
import com.chronos.job.dto.JobResponse;
import com.chronos.security.ChronosUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Job management.
 *
 * <p>Every route requires a token ({@code anyRequest().authenticated()} in SecurityConfig). The
 * per-row ownership rules are enforced in {@link JobService}, not here — a controller can only
 * check "is this caller allowed to call this method", and the real question is "is this caller
 * allowed to touch <em>this row</em>", which needs the row.
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobService jobService;
    private final JobTriggerService triggerService;

    public JobController(JobService jobService, JobTriggerService triggerService) {
        this.jobService = jobService;
        this.triggerService = triggerService;
    }

    /** USERs see their own jobs; ADMINs see everyone's. */
    @GetMapping
    public PageResponse<JobResponse> list(
            @AuthenticationPrincipal ChronosUserDetails caller,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return jobService.list(caller, includeArchived, pageable);
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable Long id,
                           @AuthenticationPrincipal ChronosUserDetails caller) {
        return jobService.get(id, caller);
    }

    /** 201 with a Location header — the job is created ENABLED and already has a nextRunAt. */
    @PostMapping
    public ResponseEntity<JobResponse> create(@Valid @RequestBody JobRequest request,
                                              @AuthenticationPrincipal ChronosUserDetails caller) {
        JobResponse created = jobService.create(request, caller);
        return ResponseEntity.created(URI.create("/jobs/" + created.id())).body(created);
    }

    /** PUT, not PATCH: the body is the job's complete new definition. */
    @PutMapping("/{id}")
    public JobResponse update(@PathVariable Long id,
                              @Valid @RequestBody JobRequest request,
                              @AuthenticationPrincipal ChronosUserDetails caller) {
        return jobService.update(id, request, caller);
    }

    /** Soft delete — the job becomes ARCHIVED and its execution history is preserved. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal ChronosUserDetails caller) {
        jobService.archive(id, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public JobResponse pause(@PathVariable Long id,
                             @AuthenticationPrincipal ChronosUserDetails caller) {
        return jobService.pause(id, caller);
    }

    @PostMapping("/{id}/resume")
    public JobResponse resume(@PathVariable Long id,
                              @AuthenticationPrincipal ChronosUserDetails caller) {
        return jobService.resume(id, caller);
    }

    /**
     * Runs the job once, right now, without disturbing its schedule.
     *
     * <p>202 rather than 200: the execution is <em>queued</em>, and the response describes a row
     * the poller has not picked up yet. Returning 200 would imply the job had run, which it has
     * not — the caller polls {@code GET /executions/{id}} to find out how it went.
     */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<ExecutionResponse> trigger(
            @PathVariable Long id,
            @AuthenticationPrincipal ChronosUserDetails caller) {
        ExecutionResponse queued = triggerService.trigger(id, caller);
        return ResponseEntity.accepted()
                .location(URI.create("/executions/" + queued.id()))
                .body(queued);
    }

    /**
     * "Show me the next few runs for this expression" — used by the create/edit form before a
     * job exists, so it takes the schedule in the body rather than a job id.
     *
     * <p>POST despite being a read: a cron expression contains characters that are awkward in a
     * query string, and this creates nothing, so the verb is the lesser evil.
     */
    @PostMapping("/cron/preview")
    public CronPreviewResponse previewCron(@Valid @RequestBody CronPreviewRequest request) {
        return jobService.previewCron(request);
    }
}
