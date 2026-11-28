package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.signup.*}.
 *
 * @param allowedEmails comma-separated addresses that may sign up; empty means anyone may. Used for public demos
 *                      where email can only be delivered to the owner's own address.
 */
@ConfigurationProperties(prefix = "pulsecheck.signup")
public record SignupProperties(@DefaultValue List<String> allowedEmails) {

    private Set<String> normalized() {
        return allowedEmails == null ? Set.of() : allowedEmails.stream()
            .map(EmailAddresses::normalize)
            .filter(e -> e != null && !e.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    /** True when anyone may sign up. */
    public boolean open() {
        return normalized().isEmpty();
    }

    /** @param normalizedEmail trimmed and lower-cased */
    public boolean allows(String normalizedEmail) {
        Set<String> allowed = normalized();
        return allowed.isEmpty() || allowed.contains(normalizedEmail);
    }
}
