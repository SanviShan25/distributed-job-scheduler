package com.sanvi.jobscheduler.worker;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    @Test
    void ceilingGrowsExponentiallyAndIsCapped() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(300));
        assertThat(policy.ceilingFor(1)).isEqualTo(2_000);
        assertThat(policy.ceilingFor(2)).isEqualTo(4_000);
        assertThat(policy.ceilingFor(5)).isEqualTo(32_000);
        assertThat(policy.ceilingFor(9)).isEqualTo(300_000);
        assertThat(policy.ceilingFor(1_000)).isEqualTo(300_000);
    }

    @Test
    void fullJitterStaysWithinZeroAndCeiling() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(300));
        for (int attempt = 1; attempt <= 20; attempt++) {
            long ceiling = policy.ceilingFor(attempt);
            for (int i = 0; i < 200; i++) {
                assertThat(policy.delayFor(attempt).toMillis()).isBetween(0L, ceiling);
            }
        }
    }

    @Test
    void jitterActuallySpreadsDelays() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(300));
        long distinct = java.util.stream.IntStream.range(0, 50)
            .mapToLong(i -> policy.delayFor(8).toMillis()).distinct().count();
        assertThat(distinct).isGreaterThan(10);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(10), Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
