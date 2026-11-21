package com.sanvi.jobscheduler.pulsecheck;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.worker.JobHandler;
import com.sanvi.jobscheduler.worker.NonRetryableJobException;

import lombok.RequiredArgsConstructor;

/**
 * {@code HTTP_CHECK} jobs: payload = monitor id. The job succeeds whenever the check was performed and
 * recorded, including when the website is down. It fails (and is retried by the scheduler) only on
 * unexpected errors such as the database being unavailable.
 */
@Component
@RequiredArgsConstructor
public class HttpCheckJobHandler implements JobHandler {
    public static final String TYPE = "HTTP_CHECK";

    private final MonitorCheckService checks;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(String payload) {
        UUID monitorId;
        try {
            monitorId = UUID.fromString(payload == null ? "" : payload.trim());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableJobException("HTTP_CHECK payload must be a monitor id, got: " + payload);
        }
        checks.runCheck(monitorId);
    }
}
