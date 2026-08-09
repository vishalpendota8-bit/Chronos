package com.chronos.execution.dto;

import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;

import java.time.Duration;
import java.time.Instant;

/**
 * The public view of one attempt.
 *
 * @param durationMs wall time of the HTTP call ({@code started_at} → {@code finished_at}), or
 *        null while it is still running. Precomputed here so the dashboard does not have to
 *        subtract timestamps in TypeScript.
 * @param queuedMs how long the row waited between being claimed and actually starting. Worth
 *        surfacing: a consistently large value means the dispatch pool is the bottleneck, not
 *        the target.
 */
public record ExecutionResponse(
        Long id,
        Long jobId,
        int attemptNo,
        Instant scheduledFor,
        Instant runAt,
        Instant claimedAt,
        Instant startedAt,
        Instant finishedAt,
        ExecutionStatus status,
        Integer responseCode,
        String responseSnippet,
        String errorMessage,
        Long durationMs,
        Long queuedMs,
        Instant createdAt
) {
    public static ExecutionResponse from(JobExecution execution) {
        return new ExecutionResponse(
                execution.getId(),
                // Reading only the id off the lazy proxy costs no extra query.
                execution.getJob().getId(),
                execution.getAttemptNo(),
                execution.getScheduledFor(),
                execution.getRunAt(),
                execution.getClaimedAt(),
                execution.getStartedAt(),
                execution.getFinishedAt(),
                execution.getStatus(),
                execution.getResponseCode(),
                execution.getResponseSnippet(),
                execution.getErrorMessage(),
                millisBetween(execution.getStartedAt(), execution.getFinishedAt()),
                millisBetween(execution.getClaimedAt(), execution.getStartedAt()),
                execution.getCreatedAt());
    }

    private static Long millisBetween(Instant from, Instant to) {
        return (from == null || to == null) ? null : Duration.between(from, to).toMillis();
    }
}
