package com.chronos.common;

/**
 * Thrown when caller-supplied input is invalid in a way Bean Validation cannot express
 * (e.g. an unparseable cron expression). M3's @RestControllerAdvice maps this to HTTP 400.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
