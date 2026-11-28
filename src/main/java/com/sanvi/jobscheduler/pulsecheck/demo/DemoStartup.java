package com.sanvi.jobscheduler.pulsecheck.demo;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * At API startup. Demo mode on: makes sure the recurring cleanup of expired sandboxes is scheduled. Demo mode off:
 * deletes every sandbox and the cleanup schedule, so turning demo mode off leaves no password-less account behind.
 * Both are idempotent and safe when several API instances start at once.
 */
@Component
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class DemoStartup {
    private final DemoAccount demo;
    private final DemoProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabled()) {
            demo.removeAll();
            return;
        }
        demo.ensureCleanupSchedule();
        log.info("Demo mode is on: up to {} sandboxes of {} each, {} monitors at most, checked every {} s or slower",
            props.maxSandboxes(), props.sandboxLifetime(), props.maxMonitors(), props.minIntervalSeconds());
    }
}
