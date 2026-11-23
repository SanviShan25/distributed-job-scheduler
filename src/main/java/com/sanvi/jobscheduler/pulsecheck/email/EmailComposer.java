package com.sanvi.jobscheduler.pulsecheck.email;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.pulsecheck.AppProperties;

import lombok.RequiredArgsConstructor;

/** Turns an {@link EmailJob} into the email to send. Runs on the worker, at send time. */
@Component
@RequiredArgsConstructor
public class EmailComposer {
    private final AppProperties app;

    public EmailSender.Email compose(EmailJob job) {
        EmailSender.Email email = switch (job.template()) {
            case VERIFY_EMAIL -> EmailTemplates.verification(job.to(),
                app.link("/verify-email", requireSecret(job)), Long.parseLong(job.param("ttlHours")));
            case PASSWORD_RESET -> EmailTemplates.passwordReset(job.to(),
                app.link("/reset-password", requireSecret(job)), Long.parseLong(job.param("ttlMinutes")));
            case ALREADY_REGISTERED -> EmailTemplates.alreadyRegistered(job.to(), app.url("/forgot-password"));
            case ALERT_DOWN -> EmailTemplates.alertDown(job.to(), job.param("monitorName"), job.param("url"),
                Instant.parse(job.param("downSince")), job.param("cause"), monitorLink(job));
            case ALERT_UP -> EmailTemplates.alertUp(job.to(), job.param("monitorName"), job.param("url"),
                Instant.parse(job.param("downSince")), Instant.parse(job.param("upAt")), job.param("cause"),
                monitorLink(job));
            case ALERTS_PAUSED -> EmailTemplates.alertsPaused(job.to(), job.param("monitorName"), job.param("url"),
                job.param("status"), Integer.parseInt(job.param("maxPerHour")), monitorLink(job));
        };
        return new EmailSender.Email(email.to(), email.subject(), email.text(), email.html(), email.headers(),
            job.idempotencyKey());
    }

    private String monitorLink(EmailJob job) {
        return app.url("/monitors/" + job.param("monitorId"));
    }

    private static String requireSecret(EmailJob job) {
        if (job.secret() == null || job.secret().isBlank()) {
            // e.g. a finished job that was requeued by hand: its token was redacted and cannot be recovered.
            throw new IllegalStateException(job.template() + " email has no link token (already redacted)");
        }
        return job.secret();
    }
}
