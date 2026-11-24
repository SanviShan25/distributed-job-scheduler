package com.sanvi.jobscheduler.pulsecheck.email;

import java.util.Map;

/**
 * Sends one transactional email. Implementations: {@link LoggingEmailSender} (development and tests) and
 * {@link ResendEmailSender}. The implementation is chosen by {@code pulsecheck.email.provider}; see
 * {@link EmailSenderConfig}.
 */
public interface EmailSender {

    /**
     * One email. Every email has a plain-text and an HTML part.
     *
     * @param headers        extra headers, e.g. {@code List-Unsubscribe} (may be empty)
     * @param idempotencyKey if set, the provider will not send a second email with the same key
     */
    record Email(String to, String subject, String text, String html, Map<String, String> headers,
        String idempotencyKey) {

        public Email {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public Email(String to, String subject, String text, String html) {
            this(to, subject, text, html, Map.of(), null);
        }
    }

    /**
     * Sends the email.
     *
     * @throws EmailSendException if it was not sent; {@link EmailSendException#isRetryable()} says whether
     *         trying again later can help
     */
    void send(Email email);
}
