package com.chronos.scheduler;

import com.chronos.config.RetryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure maths — no Spring, no Docker. */
class BackoffCalculatorTest {

    private static final BigDecimal DOUBLING = new BigDecimal("2.00");

    /** 1h cap, ±20% jitter — the shipped defaults. */
    private final BackoffCalculator calculator =
            new BackoffCalculator(new RetryProperties(3600, 0.20));

    /** No jitter, so the exponential curve itself can be asserted exactly. */
    private static final double NO_JITTER = 1.0;

    @Test
    @DisplayName("the delay doubles with each attempt: 10, 20, 40, 80")
    void growsExponentially() {
        assertThat(calculator.backoffAfter(1, 10, DOUBLING, NO_JITTER)).isEqualTo(Duration.ofSeconds(10));
        assertThat(calculator.backoffAfter(2, 10, DOUBLING, NO_JITTER)).isEqualTo(Duration.ofSeconds(20));
        assertThat(calculator.backoffAfter(3, 10, DOUBLING, NO_JITTER)).isEqualTo(Duration.ofSeconds(40));
        assertThat(calculator.backoffAfter(4, 10, DOUBLING, NO_JITTER)).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    @DisplayName("a multiplier of 1.0 gives a constant delay")
    void multiplierOfOneIsConstant() {
        BigDecimal flat = new BigDecimal("1.00");

        assertThat(calculator.backoffAfter(1, 30, flat, NO_JITTER)).isEqualTo(Duration.ofSeconds(30));
        assertThat(calculator.backoffAfter(5, 30, flat, NO_JITTER)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("growth is capped at max-backoff-sec")
    void capsAtMaximum() {
        // 10 * 2^19 would be ~1.5 days without the cap.
        assertThat(calculator.backoffAfter(20, 10, DOUBLING, NO_JITTER))
                .isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    @DisplayName("an enormous exponent saturates instead of overflowing to a negative delay")
    void hugeExponentDoesNotOverflow() {
        // Math.pow reaches Infinity here; the cap must still produce a sane duration. Repeated
        // integer multiplication would have wrapped around to a negative number.
        Duration delay = calculator.backoffAfter(1000, 300, new BigDecimal("999.99"), NO_JITTER);

        assertThat(delay).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    @DisplayName("jitter keeps the delay within ±20% of the base")
    void jitterStaysWithinBounds() {
        // Base for attempt 3 is 40s, so the window is 32s..48s.
        assertThat(calculator.backoffAfter(3, 10, DOUBLING, 0.8)).isEqualTo(Duration.ofSeconds(32));
        assertThat(calculator.backoffAfter(3, 10, DOUBLING, 1.2)).isEqualTo(Duration.ofSeconds(48));
    }

    @Test
    @DisplayName("at the cap, jitter can only reduce the delay — the maximum is a real maximum")
    void jitterNeverExceedsTheCap() {
        assertThat(calculator.backoffAfter(20, 10, DOUBLING, 1.2))
                .isEqualTo(Duration.ofSeconds(3600));
        assertThat(calculator.backoffAfter(20, 10, DOUBLING, 0.8))
                .isEqualTo(Duration.ofSeconds(2880));
    }

    @Test
    @DisplayName("the delay is never below one second")
    void neverBelowOneSecond() {
        // A 1s base with maximum downward jitter would round to 0.8s.
        assertThat(calculator.backoffAfter(1, 1, DOUBLING, 0.2))
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("the random path stays inside the jitter window")
    void randomJitterIsBounded() {
        // Base for attempt 2 is 20s => 16s..24s.
        for (int i = 0; i < 200; i++) {
            Duration delay = calculator.backoffAfter(2, 10, DOUBLING);
            assertThat(delay).isBetween(Duration.ofSeconds(16), Duration.ofSeconds(24));
        }
    }

    @Test
    @DisplayName("jitter actually varies — this is what breaks up a thundering herd")
    void randomJitterProducesDifferentDelays() {
        Set<Duration> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(calculator.backoffAfter(5, 10, DOUBLING)); // base 160s, window 128..192
        }

        // Without jitter every one of these would be exactly 160s.
        assertThat(seen).hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("a zero jitter ratio disables jitter entirely")
    void zeroJitterIsDeterministic() {
        BackoffCalculator noJitter = new BackoffCalculator(new RetryProperties(3600, 0.0));

        assertThat(noJitter.backoffAfter(3, 10, DOUBLING)).isEqualTo(Duration.ofSeconds(40));
    }
}
