package com.chronos.job.dto;

import java.time.Instant;
import java.util.List;

/**
 * @param nextRuns UTC instants. The client renders them in {@code timezone}; sending UTC keeps
 *                 this endpoint's contract identical to what {@code nextRunAt} means everywhere
 *                 else in the API.
 */
public record CronPreviewResponse(
        String cronExpr,
        String timezone,
        String description,
        List<Instant> nextRuns
) {
}
