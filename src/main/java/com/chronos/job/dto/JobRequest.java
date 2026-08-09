package com.chronos.job.dto;

import com.chronos.job.HttpMethodType;
import com.chronos.job.MisfirePolicy;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The create and update payload — one shape for both, because a job update is a full
 * replacement (PUT), not a patch.
 *
 * <p><b>Why the tuning fields are boxed types:</b> {@code null} has to mean "not supplied, use
 * the configured default", which a primitive {@code int} cannot express — it would silently
 * become 0 and fail the database check constraint. Bean Validation skips null, so the
 * {@code @Min}/{@code @Max} bounds still apply to any value that <em>is</em> supplied.
 *
 * <p>Those bounds intentionally mirror the CHECK constraints in V1__init.sql. The database is
 * the real guarantee; these exist so a bad value comes back as a readable 400 instead of a
 * constraint violation surfacing as a 500.
 *
 * <p>Absent by design: {@code status} and {@code nextRunAt}. Status changes go through
 * pause/resume/delete, and {@code nextRunAt} is derived from the cron expression — letting a
 * client set either would let it drive the scheduler directly.
 */
public record JobRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be at most 120 characters")
        String name,

        @NotBlank(message = "Target URL is required")
        @Size(max = 2048, message = "Target URL must be at most 2048 characters")
        String targetUrl,

        @NotNull(message = "HTTP method is required (GET, POST, PUT, PATCH or DELETE)")
        HttpMethodType httpMethod,

        Map<String, String> headers,

        /** Raw JSON request body, stored verbatim in a jsonb column. Null for GET/DELETE. */
        String payload,

        @NotBlank(message = "Cron expression is required")
        @Size(max = 200, message = "Cron expression must be at most 200 characters")
        String cronExpr,

        @Size(max = 64, message = "Timezone must be at most 64 characters")
        String timezone,

        @Min(value = 1, message = "maxAttempts must be at least 1")
        @Max(value = 20, message = "maxAttempts must be at most 20")
        Integer maxAttempts,

        @Min(value = 1, message = "initialBackoffSec must be at least 1")
        @Max(value = 86_400, message = "initialBackoffSec must be at most 86400")
        Integer initialBackoffSec,

        @DecimalMin(value = "1.0", message = "backoffMultiplier must be at least 1.0")
        @DecimalMax(value = "999.99", message = "backoffMultiplier must be at most 999.99")
        BigDecimal backoffMultiplier,

        @Min(value = 1, message = "timeoutSec must be at least 1")
        @Max(value = 300, message = "timeoutSec must be at most 300")
        Integer timeoutSec,

        /**
         * FIRE_NOW or SKIP. Null falls back to {@code chronos.defaults.misfire-policy}, so
         * clients written against M3-M5 keep working unchanged.
         */
        MisfirePolicy misfirePolicy
) {
}
