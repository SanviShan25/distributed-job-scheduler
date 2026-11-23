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

    /**
     * Never puts the payload (or a parser message quoting it) into an exception: exception messages become
     * the job's {@code last_error} and appear in worker logs, and the payload may hold a one-time token.
     */
    @Override
    public void handle(String payload) {
        EmailJob job;
        try {
            job = EmailOutbox.parse(payload);
        } catch (RuntimeException e) {
            // Deliberately neither the parser's message nor the exception as cause: both can quote the input.
            throw new NonRetryableJobException("malformed SEND_EMAIL payload (" + e.getClass().getSimpleName() + ")");
        }
        EmailSender.Email email;
        try {
            email = composer.compose(job);
        } catch (RuntimeException e) {
            // Composer messages are our own and never contain the secret.
            throw new NonRetryableJobException("cannot build " + job.template() + " email: " + e.getMessage());
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
