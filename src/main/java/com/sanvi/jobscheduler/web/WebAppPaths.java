package com.sanvi.jobscheduler.web;

import java.util.List;

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Which URL paths belong to the backend and which to the web app (the React single-page app built from
 * {@code web/}). The security config and the SPA fallback both use this one list, so they cannot disagree.
 *
 * <p>Everything outside {@link #BACKEND_PATTERNS} is the website: a static file if one exists, otherwise
 * {@code index.html} so the app's router can show the page. New backend endpoints must live under one of these
 * patterns ({@code WebAppIntegrationTest} checks every controller mapping), otherwise a GET to them would be
 * treated as a public website request.
 */
public final class WebAppPaths {
    private WebAppPaths() {
    }

    /** Spring path patterns; {@code /x/**} also matches {@code /x} itself. */
    public static final List<String> BACKEND_PATTERNS = List.of(
        "/api/**",            // PulseCheck JSON API (session + CSRF)
        "/jobs/**",           // scheduler API (API key)
        "/schedules/**",      // scheduler API (API key)
        "/actuator/**",       // health and metrics
        "/swagger-ui.html",   // API docs
        "/swagger-ui/**",
        "/v3/**",             // OpenAPI JSON/YAML
        "/error");            // Spring Boot's error page

    /** Hashed build output from Vite: the file name changes whenever the content does. */
    public static final String HASHED_ASSETS_PATTERN = "/assets/**";

    private static final List<PathPattern> BACKEND = BACKEND_PATTERNS.stream()
        .map(PathPatternParser.defaultInstance::parse)
        .toList();

    public static boolean isBackend(String path) {
        PathContainer container = PathContainer.parsePath(path.startsWith("/") ? path : "/" + path);
        return BACKEND.stream().anyMatch(pattern -> pattern.matches(container));
    }

    /**
     * True if a path with no matching file should get {@code index.html}: not a backend path, not a hashed asset
     * (a missing {@code /assets/app-1a2b.js} must be a 404, not HTML the browser then fails to run as a script),
     * and no file extension in the last segment ({@code /robots.txt} missing is a 404 too).
     */
    public static boolean isClientRoute(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (isBackend(normalized) || normalized.startsWith("/assets/")) {
            return false;
        }
        String lastSegment = normalized.substring(normalized.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }
}
