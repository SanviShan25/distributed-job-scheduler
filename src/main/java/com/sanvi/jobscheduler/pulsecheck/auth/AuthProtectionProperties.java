package com.sanvi.jobscheduler.pulsecheck.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.auth.*}: brute-force protection.
 *
 * @param rateLimitsEnabled       turn the login/sign-up rate limits off (tests only)
 * @param loginPerIpPerMinute     login attempts per client IP per minute
 * @param loginPerEmailPerMinute  login attempts per email address per minute
 * @param signupPerIpPerHour      sign-ups per client IP per hour
 * @param maxFailedLogins         consecutive failed logins that lock the account
 * @param lockDuration            how long a locked account stays locked
 */
@ConfigurationProperties(prefix = "pulsecheck.auth")
public record AuthProtectionProperties(
    @DefaultValue("true") boolean rateLimitsEnabled,
    @DefaultValue("10") int loginPerIpPerMinute,
    @DefaultValue("5") int loginPerEmailPerMinute,
    @DefaultValue("5") int signupPerIpPerHour,
    @DefaultValue("10") int maxFailedLogins,
    @DefaultValue("15m") Duration lockDuration) {
}
