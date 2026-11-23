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
 * @param session               login session cookie and timeouts
 */
@ConfigurationProperties(prefix = "pulsecheck")
public record PulseCheckProperties(
    @DefaultValue("dev@pulsecheck.local") String devUserEmail,
    @DefaultValue Checker checker,
    @DefaultValue("10") int maxMonitorsPerUser,
    @DefaultValue("90") int retentionDays,
    @DefaultValue("5000") int retentionBatchSize,
    @DefaultValue Session session) {

    /** When the session cookie gets the {@code Secure} attribute. */
    public enum SecureCookie {
        /** Secure unless the request is for localhost / 127.0.0.1 / ::1 (plain-HTTP local development). */
        AUTO,
        ALWAYS,
        NEVER
    }

    /**
     * @param cookieName         session cookie name
     * @param secureCookie       AUTO (default), ALWAYS or NEVER
     * @param idleTimeout        session idle timeout without "remember me"
     * @param rememberMeTimeout  idle timeout and cookie lifetime with "remember me"
     */
    public record Session(
        @DefaultValue("PULSECHECK_SESSION") String cookieName,
        @DefaultValue("AUTO") SecureCookie secureCookie,
        @DefaultValue("1d") java.time.Duration idleTimeout,
        @DefaultValue("30d") java.time.Duration rememberMeTimeout) {

        public static Session defaults() {
            return new Session("PULSECHECK_SESSION", SecureCookie.AUTO, java.time.Duration.ofDays(1),
                java.time.Duration.ofDays(30));
        }
    }

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
