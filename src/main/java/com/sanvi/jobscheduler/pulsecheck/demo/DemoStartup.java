package com.sanvi.jobscheduler.pulsecheck.demo;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * At API startup. Demo mode on: makes sure the demo account and its nightly reset schedule exist, and gives the
 * account its sample monitors if it has none (first start). Demo mode off: removes both, so turning demo mode off
 * leaves no password-less account behind. Every step is idempotent and safe when several API instances start at
 * once.
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
        demo.ensureExists();
        demo.ensureResetSchedule();
        if (demo.hasNoMonitors()) {
            demo.reset();
        }
        log.info("Demo mode is on: visitors can try the demo account; it resets on cron '{}' (UTC)", props.resetCron());
    }
}
