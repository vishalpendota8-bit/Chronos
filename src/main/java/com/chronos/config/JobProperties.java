package com.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code chronos.jobs.*} — policy limits on what a job is allowed to target.
 *
 * @param blockPrivateTargets when true, target URLs that resolve to loopback, link-local or
 *        private address ranges are rejected. See {@link com.chronos.job.JobValidator} for why
 *        this matters and why it ships disabled.
 * @param maxHeaders cap on custom headers per job — a bound on how much jsonb one row can hold
 *        and on how large a request the dispatcher can be told to build.
 * @param maxPayloadBytes cap on the JSON request body.
 */
@ConfigurationProperties(prefix = "chronos.jobs")
public record JobProperties(
        boolean blockPrivateTargets,
        int maxHeaders,
        int maxPayloadBytes
) {
}
