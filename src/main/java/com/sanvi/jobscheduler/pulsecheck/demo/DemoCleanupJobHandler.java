package com.sanvi.jobscheduler.pulsecheck.demo;

import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.worker.JobHandler;

import lombok.RequiredArgsConstructor;

/**
 * {@code DEMO_CLEANUP} jobs, created every few minutes by the {@value DemoAccount#CLEANUP_SCHEDULE_NAME} schedule:
 * deletes expired demo sandboxes and all their data. The payload is ignored. Idempotent, so a retried or duplicated
 * run is harmless; a failure (e.g. the database is unavailable) is retried by the scheduler.
 */
@Component
@RequiredArgsConstructor
public class DemoCleanupJobHandler implements JobHandler {
    public static final String TYPE = "DEMO_CLEANUP";

    private final DemoAccount demo;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(String payload) {
        demo.deleteExpired();
    }
}
