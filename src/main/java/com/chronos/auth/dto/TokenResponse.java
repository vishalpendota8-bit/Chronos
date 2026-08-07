package com.chronos.auth.dto;

/**
 * What /auth/register and /auth/login return.
 *
 * <p>The user is embedded so the client can render "logged in as …" without a second round
 * trip, and {@code expiresInSeconds} lets it schedule a re-login before requests start failing
 * rather than after.
 *
 * @param tokenType always "Bearer" — the literal prefix the client must put in the
 *                  {@code Authorization} header.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserResponse user
) {
    public static TokenResponse bearer(String accessToken, long expiresInSeconds, UserResponse user) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds, user);
    }
}
