package com.sanvi.jobscheduler.pulsecheck.email;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sanvi.jobscheduler.pulsecheck.AppProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Chooses the {@link EmailSender} from {@code pulsecheck.email.provider} and refuses to start with an
 * unsafe or incomplete configuration:
 * <ul>
 *   <li>{@code logging} while {@code pulsecheck.app.base-url} is not a localhost URL: the logging sender
 *       writes working login, verification and reset links to the log, so it must never serve real users;</li>
 *   <li>{@code resend} without an API key or without a sender address.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EmailProperties.class, AppProperties.class})
@Slf4j
public class EmailSenderConfig {

    @Bean
    EmailSender emailSender(EmailProperties email, AppProperties app) {
        return switch (email.provider()) {
            case LOGGING -> {
                if (!app.isLocalhost()) {
                    throw new IllegalStateException("pulsecheck.email.provider=logging writes working login links "
                        + "to the log and is only allowed when pulsecheck.app.base-url is a localhost URL (it is "
                        + app.baseUrl() + "). Set PULSECHECK_EMAIL_PROVIDER=resend and RESEND_API_KEY.");
                }
                log.warn("Emails are only written to the log (pulsecheck.email.provider=logging). Local development only.");
                yield new LoggingEmailSender();
            }
            case RESEND -> {
                if (email.resend().apiKey() == null || email.resend().apiKey().isBlank()) {
                    throw new IllegalStateException("pulsecheck.email.provider=resend needs an API key: set RESEND_API_KEY.");
                }
                if (email.from() == null || email.from().isBlank()) {
                    throw new IllegalStateException("pulsecheck.email.provider=resend needs a sender: set EMAIL_FROM.");
                }
                log.info("Emails are sent through Resend from {}", email.from());
                yield new ResendEmailSender(email);
            }
        };
    }
}
