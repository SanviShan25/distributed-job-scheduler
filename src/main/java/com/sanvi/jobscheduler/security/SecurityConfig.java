package com.sanvi.jobscheduler.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Two independent security setups.
 *
 * <p><b>PulseCheck ({@code /api/**}), browser sessions.</b> Users log in with email and password; the
 * session lives in Redis (Spring Session) behind an HttpOnly, SameSite=Lax cookie. CSRF protection uses the
 * single-page-app pattern: a readable {@code XSRF-TOKEN} cookie whose value must be echoed in the
 * {@code X-XSRF-TOKEN} header on every state-changing request.
 * <ul>
 *   <li>{@code /api/auth/me}, {@code /api/auth/logout}: logged-in user</li>
 *   <li>other {@code /api/auth/**} (signup, login, csrf): public</li>
 *   <li>{@code /api/monitors/**} and anything else under {@code /api}: logged-in user</li>
 * </ul>
 *
 * <p><b>Scheduler and everything else, stateless API key.</b>
 * <ul>
 *   <li>{@code /jobs/**}, {@code /schedules/**}: require a valid {@code X-API-Key}</li>
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
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Readable by JavaScript on purpose (double-submit pattern); the session cookie is the HttpOnly one. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    @Order(1)
    SecurityFilterChain pulseCheckSecurityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens) throws Exception {
        AuthenticationEntryPoint notLoggedIn = (request, response, ex) -> writeProblem(response,
            HttpStatus.UNAUTHORIZED, "Please log in", request.getRequestURI());
        AccessDeniedHandler denied = (request, response, ex) -> writeProblem(response, HttpStatus.FORBIDDEN,
            ex instanceof CsrfException
                ? "Missing or invalid CSRF token: call GET /api/auth/csrf and send the token in the X-XSRF-TOKEN header"
                : "Access denied",
            request.getRequestURI());

        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
            .securityContext(context -> context.securityContextRepository(contexts))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(notLoggedIn).accessDeniedHandler(denied))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/me", "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http, ApiSecurityProperties props, Environment env)
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
