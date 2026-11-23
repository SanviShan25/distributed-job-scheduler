package com.sanvi.jobscheduler.pulsecheck.email;

/**
 * Sends transactional email. {@link LoggingEmailSender} for now; a real provider plugs in behind this
 * interface later. Implementations should not throw for delivery problems the user cannot fix.
 */
public interface EmailSender {

    record Email(String to, String subject, String body) {}

    void send(Email email);
}
