package com.sanvi.jobscheduler.pulsecheck.api;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

class MonitorSchedulesTest {

    @Test
    void cronFiresExactlyAtTheChosenInterval() {
        ZonedDateTime start = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        for (int interval : MonitorSchedules.ALLOWED_INTERVALS) {
            for (int i = 0; i < 50; i++) {
                CronExpression cron = CronExpression.parse(MonitorSchedules.cronFor(UUID.randomUUID(), interval));
                ZonedDateTime first = cron.next(start);
                ZonedDateTime second = cron.next(first);
                ZonedDateTime third = cron.next(second);
                assertThat(Duration.between(first, second).toSeconds()).isEqualTo(interval);
                assertThat(Duration.between(second, third).toSeconds()).isEqualTo(interval);
            }
        }
    }

    @Test
    void monitorsAreSpreadAcrossTheMinute() {
        Set<String> seconds = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seconds.add(MonitorSchedules.cronFor(UUID.randomUUID(), 60).split(" ")[0]);
        }
        assertThat(seconds.size()).isGreaterThan(30);
    }

    @Test
    void sameMonitorAlwaysGetsTheSameCron() {
        UUID id = UUID.randomUUID();
        assertThat(MonitorSchedules.cronFor(id, 300)).isEqualTo(MonitorSchedules.cronFor(id, 300));
    }

    @Test
    void unsupportedIntervalIsRejected() {
        assertThatThrownBy(() -> MonitorSchedules.cronFor(UUID.randomUUID(), 30))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
