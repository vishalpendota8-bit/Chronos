package com.chronos.scheduler;

import com.chronos.common.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronServiceTest {

    private final CronService cron = new CronService();

    @Test
    @DisplayName("hourly expression yields the next top of the hour in UTC")
    void nextRunHourly() {
        Instant after = Instant.parse("2026-03-07T10:17:00Z");

        Optional<Instant> next = cron.nextRun("0 * * * *", "UTC", after);

        assertThat(next).contains(Instant.parse("2026-03-07T11:00:00Z"));
    }

    @Test
    @DisplayName("next run is strictly after the given instant, never equal to it")
    void nextRunIsStrictlyAfter() {
        Instant exactlyOnSchedule = Instant.parse("2026-03-07T11:00:00Z");

        Optional<Instant> next = cron.nextRun("0 * * * *", "UTC", exactlyOnSchedule);

        assertThat(next).contains(Instant.parse("2026-03-07T12:00:00Z"));
    }

    @Test
    @DisplayName("schedule is evaluated in the job timezone, not UTC")
    void nextRunHonoursTimezone() {
        // 09:00 in New York on a winter day (UTC-5) is 14:00Z.
        Instant after = Instant.parse("2026-01-15T00:00:00Z");

        Optional<Instant> next = cron.nextRun("0 9 * * *", "America/New_York", after);

        assertThat(next).contains(Instant.parse("2026-01-15T14:00:00Z"));
    }

    @Test
    @DisplayName("daily local time survives a daylight-saving transition")
    void nextRunAcrossDstSpringForward() {
        // US DST starts 2026-03-08. A daily 09:00 New York job must stay at 09:00 local,
        // which means the gap between consecutive runs is 23 hours, not 24.
        Instant beforeTransition = Instant.parse("2026-03-07T14:00:00Z"); // 09:00 EST

        Instant next = cron.nextRun("0 9 * * *", "America/New_York", beforeTransition).orElseThrow();

        assertThat(next).isEqualTo(Instant.parse("2026-03-08T13:00:00Z")); // 09:00 EDT
        assertThat(Duration.between(beforeTransition, next)).isEqualTo(Duration.ofHours(23));
    }

    @Test
    @DisplayName("preview returns consecutive, strictly increasing occurrences")
    void previewReturnsConsecutiveOccurrences() {
        Instant after = Instant.parse("2026-03-07T10:17:00Z");

        List<Instant> preview = cron.preview("*/15 * * * *", "UTC", after, 4);

        assertThat(preview).containsExactly(
                Instant.parse("2026-03-07T10:30:00Z"),
                Instant.parse("2026-03-07T10:45:00Z"),
                Instant.parse("2026-03-07T11:00:00Z"),
                Instant.parse("2026-03-07T11:15:00Z"));
    }

    @Test
    @DisplayName("preview is capped so a caller cannot request unbounded work")
    void previewIsCapped() {
        List<Instant> preview = cron.preview("* * * * *", "UTC", Instant.parse("2026-03-07T10:00:00Z"), 1000);

        assertThat(preview).hasSize(25);
    }

    @Test
    @DisplayName("day-of-week names and ranges parse")
    void weekdayExpression() {
        // Weekdays at 06:30. 2026-03-07 is a Saturday, so the next run is Monday the 9th.
        Instant saturday = Instant.parse("2026-03-07T08:00:00Z");

        Optional<Instant> next = cron.nextRun("30 6 * * MON-FRI", "UTC", saturday);

        assertThat(next).contains(Instant.parse("2026-03-09T06:30:00Z"));
    }

    @Test
    void describeProducesHumanText() {
        assertThat(cron.describe("0 2 * * *")).containsIgnoringCase("2");
    }

    @Test
    @DisplayName("a malformed expression fails with a caller-safe message")
    void rejectsMalformedExpression() {
        assertThatThrownBy(() -> cron.validate("not a cron", "UTC"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    @DisplayName("a 6-field Quartz-style expression is rejected by the UNIX parser")
    void rejectsQuartzExpression() {
        assertThatThrownBy(() -> cron.validate("0 0 12 * * ?", "UTC"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expected 5 fields");
    }

    @Test
    void rejectsUnknownTimezone() {
        assertThatThrownBy(() -> cron.validate("0 * * * *", "Mars/Olympus_Mons"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown timezone");
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> cron.validate("  ", "UTC")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> cron.validate("0 * * * *", " ")).isInstanceOf(BadRequestException.class);
    }
}
