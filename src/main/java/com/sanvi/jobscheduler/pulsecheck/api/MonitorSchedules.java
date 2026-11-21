package com.sanvi.jobscheduler.pulsecheck.api;

import java.util.Set;
import java.util.UUID;

/**
 * Cron expressions for monitor intervals. Each monitor gets a fixed second (and, for intervals above a
 * minute, minute) offset derived from its id, so monitors with the same interval do not all fire at
 * second 0 and hit the workers in one burst.
 */
public final class MonitorSchedules {
    public static final Set<Integer> ALLOWED_INTERVALS = Set.of(60, 300, 900, 3600);

    private MonitorSchedules() {
    }

    public static String scheduleName(UUID monitorId) {
        return "monitor:" + monitorId;
    }

    /** 6-field Spring cron for the interval, offset by the monitor id. */
    public static String cronFor(UUID monitorId, int intervalSeconds) {
        long hash = Math.abs((long) monitorId.hashCode());
        int second = (int) (hash % 60);
        int minuteOffset = (int) ((hash / 60) % Math.max(1, intervalSeconds / 60));
        return switch (intervalSeconds) {
            case 60 -> second + " * * * * *";
            case 300 -> second + " " + minuteOffset + "/5 * * * *";
            case 900 -> second + " " + minuteOffset + "/15 * * * *";
            case 3600 -> second + " " + minuteOffset + " * * * *";
            default -> throw new IllegalArgumentException("unsupported interval " + intervalSeconds);
        };
    }
}
