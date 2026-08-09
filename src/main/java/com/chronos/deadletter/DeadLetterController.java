package com.chronos.deadletter;

import com.chronos.common.PageResponse;
import com.chronos.deadletter.dto.DeadLetterResponse;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The dead-letter queue. USERs see their own; ADMINs see everyone's. */
@RestController
@RequestMapping("/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public PageResponse<DeadLetterResponse> list(
            @AuthenticationPrincipal ChronosUserDetails caller,
            @RequestParam(defaultValue = "false") boolean includeReplayed,
            @PageableDefault(size = 20) Pageable pageable) {
        // Sort is fixed to failed_at DESC by the repository methods, matching the partial index
        // ix_dead_letters_pending that backs the default "not yet replayed" view.
        return deadLetterService.list(caller, includeReplayed, pageable);
    }

    @GetMapping("/{id}")
    public DeadLetterResponse get(@PathVariable Long id,
                                  @AuthenticationPrincipal ChronosUserDetails caller) {
        return deadLetterService.get(id, caller);
    }

    /**
     * 202 Accepted, returning the newly queued attempt.
     *
     * <p>Accepted rather than OK because nothing has run yet — the attempt is queued, and the
     * poller will pick it up on its next tick.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<ExecutionResponse> replay(
            @PathVariable Long id,
            @AuthenticationPrincipal ChronosUserDetails caller) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(deadLetterService.replay(id, caller));
    }
}
