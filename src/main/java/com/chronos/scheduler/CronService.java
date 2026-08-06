package com.chronos.scheduler;

import com.chronos.common.BadRequestException;
import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a cron expression plus an IANA timezone into concrete UTC instants.
 *
 * <p><b>Why a service and not inline parsing:</b> every part of the engine that needs "when
 * does this run next" (job create, job resume, post-success enqueue, the UI preview) must
 * agree exactly. One implementation means one answer.
 *
 * <p><b>Flavour:</b> standard 5-field UNIX cron — {@code minute hour day-of-month month
 * day-of-week}. Chosen over Quartz because it is what people already know and it has no
 * {@code ?} placeholder to trip over. Tradeoff: minute is the finest granularity, so
 * sub-minute schedules are not expressible; switching to {@link CronType#QUARTZ} here is the
 * only change needed if that is ever wanted.
 *
 * <p><b>Why the timezone matters:</b> the schedule is evaluated in the job's own zone, so
 * "0 2 * * *" stays 02:00 local across daylight-saving shifts rather than drifting to 01:00
 * or 03:00 for half the year. The result is converted to a UTC {@link Instant} for storage.
 */
@Service
public class CronService {

    /** Guard against a pathological expression (e.g. Feb 30) making preview loop forever. */
    private static final int MAX_PREVIEW = 25;

    private final CronParser parser;
    private final CronDescriptor descriptor;

    public CronService() {
        this.parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
        this.descriptor = CronDescriptor.instance(Locale.ENGLISH);
    }

    /**
     * Validates expression and zone together, throwing {@link BadRequestException} with a
     * message safe to return to the caller.
     */
    public void validate(String cronExpr, String timezone) {
        parse(cronExpr);
        zoneOf(timezone);
    }

    /**
     * Next occurrence strictly after {@code after}.
     *
     * @return empty when the expression has no further occurrence (cron-utils reports this for
     *         genuinely unsatisfiable dates); callers treat that as "this job is finished".
     */
    public Optional<Instant> nextRun(String cronExpr, String timezone, Instant after) {
        ZonedDateTime from = after.atZone(zoneOf(timezone));
        return ExecutionTime.forCron(parse(cronExpr))
                .nextExecution(from)
                .map(ChronoUnitHelper::toInstant);
    }

    /** Convenience overload: next occurrence after now. */
    public Optional<Instant> nextRun(String cronExpr, String timezone) {
        return nextRun(cronExpr, timezone, Instant.now());
    }

    /**
     * The next {@code count} occurrences, used by the UI's cron preview.
     *
     * <p>Each step feeds the previous result back in, because cron-utils only ever answers
     * "the one after this instant" — there is no batch API.
     */
    public List<Instant> preview(String cronExpr, String timezone, Instant after, int count) {
        Cron cron = parse(cronExpr);
        ZoneId zone = zoneOf(timezone);
        ExecutionTime executionTime = ExecutionTime.forCron(cron);

        List<Instant> out = new ArrayList<>();
        ZonedDateTime cursor = after.atZone(zone);
        int limit = Math.min(count, MAX_PREVIEW);
        for (int i = 0; i < limit; i++) {
            Optional<ZonedDateTime> next = executionTime.nextExecution(cursor);
            if (next.isEmpty()) {
                break;
            }
            cursor = next.get();
            out.add(ChronoUnitHelper.toInstant(cursor));
        }
        return out;
    }

    /** Human-readable rendering, e.g. "every day at 2:00" — shown in the dashboard. */
    public String describe(String cronExpr) {
        return descriptor.describe(parse(cronExpr));
    }

    private Cron parse(String cronExpr) {
        if (cronExpr == null || cronExpr.isBlank()) {
            throw new BadRequestException("Cron expression must not be blank");
        }
        try {
            Cron cron = parser.parse(cronExpr.trim());
            cron.validate();
            return cron;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Invalid cron expression '" + cronExpr + "': " + e.getMessage()
                            + " (expected 5 fields: minute hour day-of-month month day-of-week)", e);
        }
    }

    private ZoneId zoneOf(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new BadRequestException("Timezone must not be blank");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) { // covers ZoneRulesException for unknown region ids
            throw new BadRequestException(
                    "Unknown timezone '" + timezone + "' (expected an IANA zone id like 'Europe/Berlin')", e);
        }
    }

    /**
     * cron-utils returns nanosecond-precision times; the database column is microsecond
     * precision. Truncating here means a value read back from Postgres equals the value we
     * computed, which matters for the (job_id, scheduled_for, attempt_no) uniqueness key.
     */
    private static final class ChronoUnitHelper {
        private ChronoUnitHelper() {
        }

        static Instant toInstant(ZonedDateTime zdt) {
            return zdt.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
    }
}
