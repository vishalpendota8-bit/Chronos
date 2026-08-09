package com.chronos.scheduler;

/**
 * The result of one HTTP attempt, decoupled from how it gets recorded.
 *
 * <p>{@code retryable} is decided here, at the point where we know what actually happened, and
 * carried forward rather than re-derived from the status code later. M5 consumes it to decide
 * between scheduling another attempt and dead-lettering.
 *
 * @param responseCode HTTP status, or null when the request never produced a response
 *        (connection refused, DNS failure, timeout).
 * @param snippet truncated response body, for debugging from the dashboard.
 * @param errorMessage null on success; a short human-readable reason otherwise.
 */
public record DispatchOutcome(
        boolean success,
        boolean retryable,
        Integer responseCode,
        String snippet,
        String errorMessage
) {
    public static DispatchOutcome success(int responseCode, String snippet) {
        return new DispatchOutcome(true, false, responseCode, snippet, null);
    }

    /**
     * A 5xx, or a timeout / connection error. The target may well work on the next attempt, so
     * M5 will back off and try again.
     */
    public static DispatchOutcome retryableFailure(Integer responseCode, String snippet, String error) {
        return new DispatchOutcome(false, true, responseCode, snippet, error);
    }

    /**
     * A 4xx. The request itself is wrong — a bad URL, a rejected payload, a missing credential —
     * and sending the identical request again will fail identically. Retrying would just be a
     * slower way to reach the same conclusion, so M5 dead-letters these immediately.
     */
    public static DispatchOutcome permanentFailure(Integer responseCode, String snippet, String error) {
        return new DispatchOutcome(false, false, responseCode, snippet, error);
    }
}
