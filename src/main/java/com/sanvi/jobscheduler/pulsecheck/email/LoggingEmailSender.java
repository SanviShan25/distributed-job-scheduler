package com.sanvi.jobscheduler.pulsecheck.email;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Development stand-in: writes each email (including its link) to the application log instead of
 * sending it. The links contain live tokens, so this must not be used once real users exist; a real
 * provider replaces it behind {@link EmailSender}.
 */
@Component
@Slf4j
public class LoggingEmailSender implements EmailSender {
    @Override
    public void send(Email email) {
        log.info("EMAIL to={} subject=\"{}\"\n{}", email.to(), email.subject(), email.body());
    }
}
