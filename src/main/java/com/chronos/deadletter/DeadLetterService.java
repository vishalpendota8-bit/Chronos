package com.chronos.deadletter;

import com.chronos.common.ConflictException;
import com.chronos.common.NotFoundException;
import com.chronos.common.PageResponse;
import com.chronos.deadletter.dto.DeadLetterResponse;
import com.chronos.execution.ExecutionRetryService;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.security.ChronosUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The dead-letter queue: everything that gave up, and the button that gives it another go.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository deadLetters;
    private final ExecutionRetryService retryService;

    public DeadLetterService(DeadLetterRepository deadLetters, ExecutionRetryService retryService) {
        this.deadLetters = deadLetters;
        this.retryService = retryService;
    }

    /**
     * Lists parked failures, newest first.
     *
     * <p>Already-replayed entries are hidden by default: the useful reading of this list is
     * "what still needs attention". They are kept rather than deleted, so {@code
     * includeReplayed=true} gives the full audit trail of what failed and what was done about it.
     */
    @Transactional(readOnly = true)
    public PageResponse<DeadLetterResponse> list(ChronosUserDetails caller, boolean includeReplayed,
                                                 Pageable pageable) {
        Page<DeadLetter> page;
        if (caller.isAdmin()) {
            page = includeReplayed
                    ? deadLetters.findAllByOrderByFailedAtDesc(pageable)
                    : deadLetters.findByReplayedAtIsNullOrderByFailedAtDesc(pageable);
        } else {
            page = includeReplayed
                    ? deadLetters.findByJobOwnerIdOrderByFailedAtDesc(caller.id(), pageable)
                    : deadLetters.findByJobOwnerIdAndReplayedAtIsNullOrderByFailedAtDesc(
                            caller.id(), pageable);
        }
        return PageResponse.from(page, DeadLetterResponse::from);
    }

    @Transactional(readOnly = true)
    public DeadLetterResponse get(Long id, ChronosUserDetails caller) {
        return DeadLetterResponse.from(loadForCaller(id, caller));
    }

    /**
     * Queues a fresh attempt for a dead execution and stamps the entry as replayed.
     *
     * <p>The dead letter is <em>not</em> deleted. It is the record that this occurrence failed,
     * and destroying it on replay would erase the only evidence that anything went wrong. If the
     * replay fails too, the new attempt produces its own dead letter, so the queue still surfaces
     * the problem.
     */
    @Transactional
    public ExecutionResponse replay(Long id, ChronosUserDetails caller) {
        DeadLetter deadLetter = loadForCaller(id, caller);

        if (deadLetter.getReplayedAt() != null) {
            throw new ConflictException(
                    "Dead letter " + id + " was already replayed at " + deadLetter.getReplayedAt());
        }

        // Runs in this transaction, so the new attempt and the replayed_at stamp commit together
        // — there is no window where one exists without the other.
        ExecutionResponse retry = retryService.retry(deadLetter.getExecution().getId(), caller);

        deadLetter.setReplayedAt(Instant.now());
        log.info("User {} replayed dead letter {} as execution {}", caller.id(), id, retry.id());

        return retry;
    }

    /** Ownership runs through the denormalised job — same 404-not-403 rule as everywhere else. */
    private DeadLetter loadForCaller(Long id, ChronosUserDetails caller) {
        DeadLetter deadLetter = deadLetters.findById(id)
                .orElseThrow(() -> NotFoundException.of("Dead letter", id));

        if (!caller.isAdmin() && !deadLetter.getJob().getOwner().getId().equals(caller.id())) {
            throw NotFoundException.of("Dead letter", id);
        }
        return deadLetter;
    }
}
