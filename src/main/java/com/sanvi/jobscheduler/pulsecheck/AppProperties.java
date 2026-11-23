package com.sanvi.jobscheduler.pulsecheck;

import java.net.URI;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.app.*}: where the PulseCheck web app lives. Links in emails point here.
 *
 * @param baseUrl e.g. {@code https://pulsecheck.example.com} (env {@code PULSECHECK_APP_BASE_URL})
 */
@ConfigurationProperties(prefix = "pulsecheck.app")
public record AppProperties(@DefaultValue("http://localhost:8080") String baseUrl) {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /** {@code baseUrl + path}, with exactly one slash between them. */
    public String url(String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    public String link(String path, String token) {
        return url(path) + "?token=" + token;
    }

    /** True if the app is only reachable from this machine (local development). */
    public boolean isLocalhost() {
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
