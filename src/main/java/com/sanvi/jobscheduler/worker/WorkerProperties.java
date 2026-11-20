package com.sanvi.jobscheduler.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code worker.*} settings. {@code default-timeout-seconds} is also read by the API, which stamps it
 * onto jobs submitted without an explicit timeout.
 */
@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
    String id,
    @DefaultValue("4") int concurrency,
    @DefaultValue("300") int defaultTimeoutSeconds,
    @DefaultValue("30") int leaseSeconds,
    @DefaultValue("25") int shutdownGraceSeconds,
    @DefaultValue("1") int baseBackoffSeconds,
    @DefaultValue("300") int maxBackoffSeconds,
    @DefaultValue("2s") Duration pollTimeout) {

    public Duration lease() {
        return Duration.ofSeconds(leaseSeconds);
    }

    /** Renew well before expiry so one slow renewal doesn't lose the lease. */
    public Duration leaseRenewInterval() {
        return Duration.ofMillis(Math.max(100, lease().toMillis() / 3));
    }
}
