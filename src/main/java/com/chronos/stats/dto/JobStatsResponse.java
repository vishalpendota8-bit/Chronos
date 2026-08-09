package com.chronos.stats.dto;

import com.chronos.execution.ExecutionStatus;
import com.chronos.job.JobStatus;

import java.time.Instant;

/**
 * One job's health, for the detail page.
 *
 * <p>All-time rather than windowed, unlike {@link StatsResponse}. A single job's history is small
 * enough to summarise whole, and "this job has succeeded 400 times and died twice" is exactly the
 * shape of the question someone opening one job's page is asking.
 *
 * <p>{@code lastRunAt} and {@code lastRunStatus} describe the most recent attempt that actually
 * finished, so the page can lead with "last run: 12 minutes ago, succeeded" without a second
 * request.
 *
 * @param averageDurationSec mean wall time of completed attempts. Null when the job has never
 *        completed one — distinct from 0.0, which would claim it always returns instantly.
 * @param successRate see {@link StatsResponse#successRate()} for why failed attempts are excluded.
 */
public record JobStatsResponse(
        Long jobId,
        String jobName,
        JobStatus status,
        Instant nextRunAt,

        long totalExecutions,
        long succeeded,
        long failed,
        long dead,
        long pending,
        Double successRate,
        Double averageDurationSec,

        long pendingDeadLetters,
        Instant lastRunAt,
        ExecutionStatus lastRunStatus
) {
}
