package com.sanvi.jobscheduler.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code worker.*} settings. {@code default-timeout-seconds} is also read by the API, which stamps it
 * onto jobs submitted without an explicit timeout.
 *
 * @param leaseSeconds        lease granted on claim and on every renewal (database clock)
 * @param leaseRenewInterval  how often a running job renews its lease; must be well below the lease
 */
@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
    String id,
    @DefaultValue("4") int concurrency,
    @DefaultValue("300") int defaultTimeoutSeconds,
    @DefaultValue("30") int leaseSeconds,
    @DefaultValue("10s") Duration leaseRenewInterval,
    @DefaultValue("25") int shutdownGraceSeconds,
    @DefaultValue("1") int baseBackoffSeconds,
    @DefaultValue("300") int maxBackoffSeconds,
    @DefaultValue("2s") Duration pollTimeout) {

    public WorkerProperties {
        if (leaseSeconds < 1) {
            throw new IllegalArgumentException("worker.lease-seconds must be at least 1");
        }
        if (leaseRenewInterval.isNegative() || leaseRenewInterval.isZero()
                || leaseRenewInterval.compareTo(Duration.ofSeconds(leaseSeconds)) >= 0) {
            throw new IllegalArgumentException(
                "worker.lease-renew-interval must be positive and shorter than worker.lease-seconds");
        }
    }
}
