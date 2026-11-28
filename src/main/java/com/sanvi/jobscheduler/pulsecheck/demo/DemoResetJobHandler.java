package com.sanvi.jobscheduler.pulsecheck.demo;

import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.worker.JobHandler;

import lombok.RequiredArgsConstructor;

/**
 * {@code DEMO_RESET} jobs, created nightly by the {@value DemoAccount#RESET_SCHEDULE_NAME} schedule: puts the demo
 * account back to its sample monitors. The payload is ignored. Idempotent, so a retried or duplicated run is
 * harmless; a failure (e.g. the database is unavailable) is retried by the scheduler.
 */
@Component
@RequiredArgsConstructor
public class DemoResetJobHandler implements JobHandler {
    public static final String TYPE = "DEMO_RESET";

    private final DemoAccount demo;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(String payload) {
        demo.reset();
    }
}
