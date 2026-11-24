package com.sanvi.jobscheduler.pulsecheck.email;

import lombok.extern.slf4j.Slf4j;

/**
 * Development stand-in: writes each email's plain-text part (including its links) to the application log
 * instead of sending it. Those links are live one-time tokens, which is why {@link EmailSenderConfig}
 * refuses to start with this sender unless the app runs on localhost.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {
    @Override
    public void send(Email email) {
        log.info("EMAIL to={} subject=\"{}\"\n{}", email.to(), email.subject(), email.text());
    }
}
