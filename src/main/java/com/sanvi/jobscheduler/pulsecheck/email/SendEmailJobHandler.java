package com.sanvi.jobscheduler.pulsecheck.email;

import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.worker.JobHandler;
import com.sanvi.jobscheduler.worker.NonRetryableJobException;

import lombok.RequiredArgsConstructor;

/**
 * {@code SEND_EMAIL}: renders and sends one email. Provider problems that may pass (429, 5xx, timeouts)
 * fail the job so the scheduler retries it with backoff; a rejection that would fail again (invalid
 * address, unverified sender, bad API key) or a malformed payload sends it straight to DEAD.
 */
@Component
@RequiredArgsConstructor
public class SendEmailJobHandler implements JobHandler {
    private final EmailComposer composer;
    private final EmailSender sender;

    @Override
    public String type() {
        return EmailOutbox.JOB_TYPE;
    }

    @Override
    public void handle(String payload) {
        EmailSender.Email email;
        try {
            email = composer.compose(EmailOutbox.parse(payload));
        } catch (RuntimeException e) {
            throw new NonRetryableJobException("cannot build email: " + e.getMessage(), e);
        }
        try {
            sender.send(email);
        } catch (EmailSendException e) {
            if (e.isRetryable()) {
                throw e;
            }
            throw new NonRetryableJobException(e.getMessage(), e);
        }
    }
}
