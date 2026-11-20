package com.sanvi.jobscheduler.schedule;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.scheduling.support.CronExpression;

import com.sanvi.jobscheduler.api.ApiProblems;

/**
 * Cron parsing for schedules. Uses Spring's {@link CronExpression} (6 fields, seconds first, plus
 * macros like {@code @hourly}); a classic 5-field Unix expression is accepted and gets {@code 0} seconds.
 */
public final class CronSupport {
    private CronSupport() {
    }

    public static String normalize(String expression) {
        String trimmed = expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
        if (!trimmed.startsWith("@") && trimmed.split(" ").length == 5) {
            return "0 " + trimmed;
        }
        return trimmed;
    }

    public static CronExpression parse(String normalized) {
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw ApiProblems.badRequest("Invalid cron expression '" + normalized + "': " + e.getMessage());
        }
    }

    public static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
        } catch (DateTimeException e) {
            throw ApiProblems.badRequest("Unknown timezone '" + timezone + "'");
        }
    }

    /** First fire time strictly after {@code after}, evaluated in {@code zone}; null if there is none. */
    public static Instant next(String cron, String timezone, Instant after) {
        ZonedDateTime next = parse(cron).next(after.atZone(zone(timezone)));
        return next == null ? null : next.toInstant();
    }
}
