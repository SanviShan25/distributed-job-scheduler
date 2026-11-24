package com.sanvi.jobscheduler.pulsecheck.email;

import java.util.Map;

/**
 * Payload of a {@code SEND_EMAIL} job: which template to render, for whom, with which parameters. A one-time
 * token needed for a link travels in {@code secret} and nowhere else, so it can be removed when the job
 * finishes (see the V9 trigger).
 */
public record EmailJob(Template template, String to, Map<String, String> params, String idempotencyKey,
    String secret) {

    public enum Template {
        VERIFY_EMAIL,
        PASSWORD_RESET,
        ALREADY_REGISTERED,
        ALERT_DOWN,
        ALERT_UP,
        ALERTS_PAUSED,
        CONFIRM_ALERT_RECIPIENT
    }

    public EmailJob {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public String param(String name) {
        String value = params.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing email parameter '" + name + "' for " + template);
        }
        return value;
    }
}
