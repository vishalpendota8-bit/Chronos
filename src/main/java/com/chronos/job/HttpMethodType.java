package com.chronos.job;

/**
 * The HTTP verbs a job may target. Deliberately narrower than the full HTTP spec: a scheduler
 * uses at-least-once delivery, so it should only invoke verbs a caller can reasonably make
 * idempotent (helped by the X-Idempotency-Key header sent from M4 on).
 */
public enum HttpMethodType {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE
}
