package com.chronos.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape every failing request returns, so the React client in M8 has exactly
 * one branch to write.
 *
 * <p>Tradeoff: this is a hand-rolled body rather than RFC 7807 {@code ProblemDetail}. Boot 3
 * can produce ProblemDetail automatically, but it omits per-field validation errors, which is
 * the most useful part of a 400 for a form-driven UI.
 *
 * @param fieldErrors populated only for Bean Validation failures; omitted from JSON otherwise.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }

    public static ApiError of(int status, String error, String message, String path,
                              List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors);
    }
}
