package com.chronos.deadletter.dto;

import com.chronos.deadletter.DeadLetter;

import java.time.Instant;

/**
 * A parked failure, as the dashboard shows it.
 *
 * <p>{@code jobName} and {@code attemptNo} are denormalised into the response so the list is
 * readable without a second call per row — the repository's {@code @EntityGraph} makes that one
 * query rather than N+1.
 */
public record DeadLetterResponse(
        Long id,
        Long jobId,
        String jobName,
        Long executionId,
        int attemptNo,
        Integer responseCode,
        String reason,
        Instant failedAt,
        Instant replayedAt,
        boolean replayed
) {
    public static DeadLetterResponse from(DeadLetter deadLetter) {
        return new DeadLetterResponse(
                deadLetter.getId(),
                deadLetter.getJob().getId(),
                deadLetter.getJob().getName(),
                deadLetter.getExecution().getId(),
                deadLetter.getExecution().getAttemptNo(),
                deadLetter.getExecution().getResponseCode(),
                deadLetter.getReason(),
                deadLetter.getFailedAt(),
                deadLetter.getReplayedAt(),
                deadLetter.getReplayedAt() != null);
    }
}
