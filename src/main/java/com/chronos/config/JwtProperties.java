package com.chronos.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds the {@code chronos.jwt.*} block.
 *
 * <p><b>Why a typed properties record instead of {@code @Value}:</b> the secret and expiry are
 * read in two different places (issuing and parsing) and a typo in an {@code @Value} string
 * fails at runtime. Binding once, validated at startup, means a misconfigured deployment
 * refuses to boot rather than issuing tokens nobody can verify.
 *
 * @param secret HMAC key material. Must be at least 32 bytes for HS256 — jjwt rejects
 *               anything shorter outright, which is a feature, not an obstacle.
 * @param issuer the {@code iss} claim; parsing requires it to match, so tokens minted by a
 *               different Chronos deployment are rejected even if the secret leaked into both.
 * @param expiry how long an access token stays valid.
 */
@Validated
@ConfigurationProperties(prefix = "chronos.jwt")
public record JwtProperties(

        @NotBlank(message = "chronos.jwt.secret must be set")
        String secret,

        @NotBlank(message = "chronos.jwt.issuer must be set")
        String issuer,

        @NotNull(message = "chronos.jwt.expiry must be set")
        Duration expiry
) {
    /** The value shipped in application.yml, refused outside the `dev` profile. */
    public static final String DEV_SECRET = "dev-only-insecure-secret-change-me-0123456789abcdef";

    /** HS256 requires a key of at least 256 bits. */
    public static final int MIN_SECRET_BYTES = 32;
}
