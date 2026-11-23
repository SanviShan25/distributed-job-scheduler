package com.sanvi.jobscheduler.support;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import jakarta.servlet.http.Cookie;

/**
 * Adds a valid double-submit CSRF token (same random value in the XSRF-TOKEN cookie and the X-XSRF-TOKEN
 * header), exactly what a browser app sends.
 *
 * <p>Use this instead of Spring Security's {@code csrf()} test helper: that helper permanently swaps the CSRF
 * repository inside the shared filter chain for a session-based test repository, which breaks real
 * cookie-based CSRF for every later test that shares the Spring context.
 */
public final class CsrfTestSupport {
    private CsrfTestSupport() {
    }

    public static RequestPostProcessor validCsrf() {
        return request -> {
            String token = UUID.randomUUID().toString();
            Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            request.setCookies(Stream.concat(
                Arrays.stream(existing).filter(c -> !c.getName().equals("XSRF-TOKEN")),
                Stream.of(new Cookie("XSRF-TOKEN", token))).toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }
}
