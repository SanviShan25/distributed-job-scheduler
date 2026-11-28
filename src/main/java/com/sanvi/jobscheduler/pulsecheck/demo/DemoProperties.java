package com.sanvi.jobscheduler.pulsecheck.demo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.demo.*}: private, temporary demo sandboxes for public demos. The caps keep a free-tier
 * deployment within its limits.
 *
 * @param enabled            turn demo mode on ("Try the demo", {@code POST /api/auth/demo}, cleanup). Off: every
 *                           sandbox and the cleanup schedule are removed at startup.
 * @param sandboxLifetime    a sandbox is deleted this long after it was created
 * @param maxSandboxes       sandboxes that may exist at once; when full, "Try the demo" answers 503
 * @param maxMonitors        monitors per sandbox
 * @param minIntervalSeconds shortest check interval for sandbox monitors
 * @param cleanupCron        when expired sandboxes are deleted (6-field Spring cron, UTC)
 */
@ConfigurationProperties(prefix = "pulsecheck.demo")
public record DemoProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("3h") Duration sandboxLifetime,
    @DefaultValue("20") int maxSandboxes,
    @DefaultValue("3") int maxMonitors,
    @DefaultValue("300") int minIntervalSeconds,
    @DefaultValue("0 */5 * * * *") String cleanupCron) {
}
