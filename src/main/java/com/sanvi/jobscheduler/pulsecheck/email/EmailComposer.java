package com.sanvi.jobscheduler.pulsecheck.email;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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
                Instant.parse(job.param("downSince")), job.param("cause"), monitorLink(job), unsubscribeLink(job));
            case ALERT_UP -> EmailTemplates.alertUp(job.to(), job.param("monitorName"), job.param("url"),
                Instant.parse(job.param("downSince")), Instant.parse(job.param("upAt")), job.param("cause"),
                monitorLink(job), unsubscribeLink(job));
            case ALERTS_PAUSED -> EmailTemplates.alertsPaused(job.to(), job.param("monitorName"), job.param("url"),
                job.param("status"), Integer.parseInt(job.param("maxPerHour")), monitorLink(job), unsubscribeLink(job));
            case CONFIRM_ALERT_RECIPIENT -> EmailTemplates.confirmAlertRecipient(job.to(), job.param("monitorName"),
                job.param("url"), app.link(CONFIRM_PATH, requireSecret(job)), Long.parseLong(job.param("ttlHours")));
        };
        Map<String, String> headers = new HashMap<>(email.headers());
        if (isAlert(job.template())) {
            // RFC 2369 + RFC 8058: mail clients show an "Unsubscribe" button that POSTs to this URL.
            headers.put("List-Unsubscribe", "<" + unsubscribeLink(job) + ">");
            headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        return new EmailSender.Email(email.to(), email.subject(), email.text(), email.html(), headers,
            job.idempotencyKey());
    }

    /** Public pages (no login) for alert recipients; the token in the link is the only credential. */
    public static final String CONFIRM_PATH = "/alert-recipients/confirm";
    public static final String UNSUBSCRIBE_PATH = "/alert-recipients/unsubscribe";

    private static boolean isAlert(EmailJob.Template template) {
        return template == EmailJob.Template.ALERT_DOWN || template == EmailJob.Template.ALERT_UP
            || template == EmailJob.Template.ALERTS_PAUSED;
    }

    private String unsubscribeLink(EmailJob job) {
        return app.link(UNSUBSCRIBE_PATH, requireSecret(job));
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
