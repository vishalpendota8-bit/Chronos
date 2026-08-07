package com.chronos.common;

/**
 * Thrown when a request is well-formed but collides with existing state — a duplicate email at
 * registration, a duplicate job name in M3. Maps to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
