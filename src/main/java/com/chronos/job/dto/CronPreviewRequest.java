package com.chronos.job.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "What would this schedule actually do?" — asked by the create/edit form in M8 while the user
 * is still typing, before any job exists.
 */
public record CronPreviewRequest(

        @NotBlank(message = "Cron expression is required")
        @Size(max = 200, message = "Cron expression must be at most 200 characters")
        String cronExpr,

        @Size(max = 64, message = "Timezone must be at most 64 characters")
        String timezone,

        // Upper bound matches CronService.MAX_PREVIEW; asking for more is a client bug.
        @Min(value = 1, message = "count must be at least 1")
        @Max(value = 25, message = "count must be at most 25")
        Integer count
) {
}
