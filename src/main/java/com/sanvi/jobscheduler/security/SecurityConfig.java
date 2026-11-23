package com.sanvi.jobscheduler.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless API-key security.
 * <ul>
 *   <li>{@code /jobs/**}, {@code /schedules/**}, {@code /api/**} (PulseCheck): require a valid {@code X-API-Key}</li>
 *   <li>{@code /actuator/health/**}, {@code /actuator/prometheus}: open. On the API these are served on
 *       the separate management port ({@code MANAGEMENT_PORT}, default 9091), which Compose does not
 *       publish, so they are only reachable from the internal network (Prometheus, health checks).</li>
 *   <li>Swagger UI / OpenAPI docs: open (the docs are not secret; calling the API still needs a key)</li>
 *   <li>everything else: denied</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityProperties props, Environment env)
            throws Exception {
        if (env.acceptsProfiles(Profiles.of("api")) && props.configuredKeys().isEmpty()) {
            throw new IllegalStateException(
                "No API keys configured: set API_KEYS (comma-separated) or security.api-keys");
        }
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> writeProblem(response,
            HttpStatus.UNAUTHORIZED, "Missing or invalid " + ApiKeyAuthenticationFilter.HEADER + " header",
            request.getRequestURI());
        AccessDeniedHandler denied = (request, response, ex) -> writeProblem(response, HttpStatus.FORBIDDEN,
            "Access denied", request.getRequestURI());

        http
            .csrf(csrf -> csrf.disable()) // no cookies or sessions: CSRF does not apply
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ApiKeyAuthenticationFilter(props.configuredKeys(), entryPoint),
                AuthorizationFilter.class)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/jobs", "/jobs/**", "/schedules", "/schedules/**").authenticated()
                // PulseCheck sign-up is public.
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/signup").permitAll()
                // PulseCheck: API key for now; real user login comes later.
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail, String instance)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
            + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\",\"instance\":\""
            + instance.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
