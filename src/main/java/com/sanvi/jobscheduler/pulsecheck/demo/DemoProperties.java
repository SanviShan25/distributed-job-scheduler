package com.sanvi.jobscheduler.pulsecheck.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.demo.*}: the shared demo account for public demos.
 *
 * @param enabled     turn demo mode on ("Try the demo", {@code POST /api/auth/demo}, nightly reset). Off: the demo
 *                    account and its reset schedule are removed at startup.
 * @param email       the demo account's address. Must be undeliverable ({@code .invalid}, RFC 2606): nothing is
 *                    ever sent to it, and nobody can own it.
 * @param maxMonitors monitors the demo account may have (lower than for normal accounts)
 * @param resetCron   when the nightly reset runs (6-field Spring cron, UTC)
 */
@ConfigurationProperties(prefix = "pulsecheck.demo")
public record DemoProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("demo@pulsecheck.invalid") String email,
    @DefaultValue("5") int maxMonitors,
    @DefaultValue("0 0 4 * * *") String resetCron) {
}
