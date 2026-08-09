package com.chronos.scheduler;

import com.chronos.config.RetryProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Works out how long to wait before the next attempt.
 *
 * <p><b>New concept — exponential backoff:</b> when a target is failing, retrying immediately
 * and repeatedly makes things worse: the target is probably struggling, and a tight retry loop
 * is extra load at exactly the wrong moment. So each attempt waits longer than the last —
 * {@code initial × multiplier^(attempt-1)} — which retries quickly for a blip and slowly for a
 * genuine outage.
 *
 * <p><b>New concept — jitter:</b> imagine 500 jobs all pointed at the same service, which goes
 * down. They all fail at once, all compute the same backoff, and all retry at the same instant —
 * a "thundering herd" that can knock the service over again just as it recovers. Multiplying
 * each delay by a random factor in {@code [1-ratio, 1+ratio]} smears those retries across a
 * window so they arrive spread out instead of as a spike.
 */
@Component
public class BackoffCalculator {

    private final RetryProperties properties;

    public BackoffCalculator(RetryProperties properties) {
        this.properties = properties;
    }

    /**
     * Delay before the attempt that follows {@code failedAttemptNo}.
     *
     * @param failedAttemptNo the 1-based attempt that just failed. Attempt 1 failing waits
     *                        {@code initialBackoffSec} (exponent 0), attempt 2 waits
     *                        {@code initial × multiplier}, and so on.
     */
    public Duration backoffAfter(int failedAttemptNo, int initialBackoffSec, BigDecimal multiplier) {
        double ratio = properties.jitterRatio();
        // nextDouble is exclusive at the top, which is irrelevant at this precision.
        double jitterFactor = ratio <= 0
                ? 1.0
                : ThreadLocalRandom.current().nextDouble(1.0 - ratio, 1.0 + ratio);

        return backoffAfter(failedAttemptNo, initialBackoffSec, multiplier, jitterFactor);
    }

    /**
     * Deterministic variant, so the maths can be tested without randomness.
     *
     * <p>Order of operations matters here: the exponential growth is capped <em>before</em>
     * jitter is applied, and the result is clamped to the cap again afterwards. The second clamp
     * means that once a job has reached the ceiling, jitter can only pull the delay down, never
     * push it past the configured maximum — a small downward bias at the cap, accepted because
     * "max backoff" reading as a genuine maximum is worth more than perfect symmetry.
     */
    Duration backoffAfter(int failedAttemptNo, int initialBackoffSec, BigDecimal multiplier,
                          double jitterFactor) {
        int exponent = Math.max(0, failedAttemptNo - 1);

        // double, not BigDecimal: this feeds a sleep-until timestamp, so exactness buys nothing,
        // and Math.pow saturates at Infinity instead of overflowing to a negative number the way
        // repeated integer multiplication would.
        double growth = Math.pow(multiplier.doubleValue(), exponent);
        double seconds = initialBackoffSec * growth;

        // Cap first, so an Infinity from a large exponent cannot survive into the arithmetic.
        seconds = Math.min(seconds, properties.maxBackoffSec());
        seconds = seconds * jitterFactor;

        // Never below one second: a "retry" that happens instantly is not a backoff, and would
        // let a fast-failing target spin the dispatch pool.
        long clamped = Math.round(Math.max(1.0, Math.min(seconds, properties.maxBackoffSec())));
        return Duration.ofSeconds(clamped);
    }
}
