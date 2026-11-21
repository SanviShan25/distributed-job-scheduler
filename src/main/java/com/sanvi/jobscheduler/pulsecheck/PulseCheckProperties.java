package com.sanvi.jobscheduler.pulsecheck;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.*} settings.
 *
 * @param devUserEmail account seeded by {@link DevUserSeeder} under the {@code dev} profile
 * @param checker      HTTP check behaviour and SSRF guard rails
 * @param maxMonitorsPerUser    monitors one user may create
 * @param retentionDays         check results older than this are deleted daily
 * @param retentionBatchSize    rows deleted per statement by the retention job
 */
@ConfigurationProperties(prefix = "pulsecheck")
public record PulseCheckProperties(
    @DefaultValue("dev@pulsecheck.local") String devUserEmail,
    @DefaultValue Checker checker,
    @DefaultValue("10") int maxMonitorsPerUser,
    @DefaultValue("90") int retentionDays,
    @DefaultValue("5000") int retentionBatchSize) {

    /**
     * @param timeout       total budget for one check, across all redirect hops
     * @param maxRedirects  redirects followed (each hop is re-validated)
     * @param maxBodyBytes  response bytes read at most; the rest is discarded
     * @param userAgent     sent with every check
     * @param allowLoopback TEST ONLY: lets checks reach 127.0.0.0/8 and ::1 (local test servers).
     *                      Must stay false anywhere real users can create monitors.
     */
    public record Checker(
        @DefaultValue("10s") Duration timeout,
        @DefaultValue("5") int maxRedirects,
        @DefaultValue("65536") int maxBodyBytes,
        @DefaultValue("PulseCheck/1.0") String userAgent,
        @DefaultValue("false") boolean allowLoopback) {

        public static Checker defaults() {
            return new Checker(Duration.ofSeconds(10), 5, 65_536, "PulseCheck/1.0", false);
        }
    }
}
