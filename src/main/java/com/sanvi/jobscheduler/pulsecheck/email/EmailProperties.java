package com.sanvi.jobscheduler.pulsecheck.email;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.email.*}.
 *
 * @param provider which sender to use: {@code logging} (default; development and tests) or {@code resend}
 * @param from     sender, e.g. {@code PulseCheck <alerts@example.com>} (env {@code EMAIL_FROM})
 * @param resend   Resend API settings
 */
@ConfigurationProperties(prefix = "pulsecheck.email")
public record EmailProperties(
    @DefaultValue("logging") Provider provider,
    @DefaultValue("PulseCheck <onboarding@resend.dev>") String from,
    @DefaultValue Resend resend) {

    public enum Provider { LOGGING, RESEND }

    /**
     * @param apiKey  Resend API key (env {@code RESEND_API_KEY}); required when the provider is {@code resend}
     * @param baseUrl API base URL; only changed in tests
     * @param timeout total time allowed for one API call
     */
    public record Resend(
        String apiKey,
        @DefaultValue("https://api.resend.com") String baseUrl,
        @DefaultValue("10s") Duration timeout) {
    }
}
