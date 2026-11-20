package com.sanvi.jobscheduler.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code security.*}: API keys (comma-separated in the {@code API_KEYS} env var) and the
 * per-key rate limit on {@code POST /jobs}.
 */
@ConfigurationProperties(prefix = "security")
public record ApiSecurityProperties(
    @DefaultValue List<String> apiKeys,
    @DefaultValue RateLimit rateLimit) {

    /**
     * Token bucket per API key: up to {@code capacity} requests in a burst, refilled at
     * {@code refillPerSecond}.
     */
    public record RateLimit(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("200") int capacity,
        @DefaultValue("50") double refillPerSecond) {
    }

    public List<String> configuredKeys() {
        return apiKeys.stream().map(String::trim).filter(key -> !key.isEmpty()).toList();
    }
}
