package com.chronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Binds {@code chronos.defaults.*} — the retry/timeout settings applied when a create request
 * omits them.
 *
 * <p><b>Why defaults live in configuration rather than in the DTO:</b> the database already
 * carries column defaults, but those only apply to raw SQL inserts; JPA always writes every
 * column. Putting them here means one place decides, an operator can retune them per
 * environment, and the value that ends up in the row is the value the API echoes back.
 */
@ConfigurationProperties(prefix = "chronos.defaults")
public record JobDefaults(
        String timezone,
        int maxAttempts,
        int initialBackoffSec,
        BigDecimal backoffMultiplier,
        int timeoutSec
) {
}
