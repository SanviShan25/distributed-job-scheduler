package com.sanvi.jobscheduler.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.sanvi.jobscheduler.web.WebAppPaths;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Independent security setups.
 *
 * <p><b>Alert email links ({@code /alert-recipients/confirm|unsubscribe}), public.</b> The single-use token in
 * the link is the credential; no session, no CSRF.
 *
 * <p><b>PulseCheck ({@code /api/**}), browser sessions.</b> Users log in with email and password; the
 * session lives in Redis (Spring Session) behind an HttpOnly, SameSite=Lax cookie. CSRF protection uses the
 * single-page-app pattern: a readable {@code XSRF-TOKEN} cookie whose value must be echoed in the
 * {@code X-XSRF-TOKEN} header on every state-changing request.
 * <ul>
 *   <li>public: signup, login, csrf, verify, forgot-password, reset-password</li>
 *   <li>logged-in user: me, logout, resend-verification, change-password, account (DELETE)</li>
 *   <li>{@code /api/monitors/**} and anything else under {@code /api}: logged-in user</li>
 * </ul>
 *
 * <p><b>The website (api profile): every GET/HEAD outside {@link WebAppPaths#BACKEND_PATTERNS}, public.</b>
 * Static files of the React app, and {@code index.html} for client-side routes. Strict security headers
 * (Content-Security-Policy, no referrer, no framing).
 *
 * <p><b>Scheduler and everything else, stateless API key.</b>
 * <ul>
 *   <li>{@code /jobs/**}, {@code /schedules/**}: require a valid {@code X-API-Key}</li>
 *   <li>{@code /actuator/health/**}, {@code /actuator/prometheus}: open. On the API these are served on
 *       the separate management port ({@code MANAGEMENT_PORT}, default 9091), which Compose does not
 *       publish, so they are only reachable from the internal network (Prometheus, health checks).</li>
 *   <li>Swagger UI / OpenAPI docs: open (the docs are not secret; calling the API still needs a key)</li>
 *   <li>everything else (e.g. a POST to a website path): denied</li>
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

    /**
     * Confirmation and unsubscribe links from alert emails: public, stateless, no CSRF. The single-use token
     * in the request is the credential, so a forged cross-site request would need the token anyway, and mail
     * providers' RFC 8058 one-click unsubscribe POSTs cannot carry a CSRF token or session.
     */
    @Bean
    @Order(0)
    SecurityFilterChain alertRecipientLinksSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/alert-recipients/confirm", "/alert-recipients/unsubscribe")
            .csrf(csrf -> csrf.disable())
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain pulseCheckSecurityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens) throws Exception {
        AuthenticationEntryPoint notLoggedIn = (request, response, ex) -> writeProblem(response,
            HttpStatus.UNAUTHORIZED, "Please log in", request.getRequestURI(), null);
        AccessDeniedHandler denied = (request, response, ex) -> writeProblem(response, HttpStatus.FORBIDDEN,
            ex instanceof CsrfException
                ? "Missing or invalid CSRF token: call GET /api/auth/csrf and send the token in the X-XSRF-TOKEN header"
                : "Access denied",
            request.getRequestURI(), ex instanceof CsrfException ? CSRF_INVALID_CODE : null);

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
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/verify",
                    "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                .anyRequest().authenticated());
        return http.build();
    }

    /**
     * Content-Security-Policy for the built web app: only its own scripts, styles, images and API (no inline
     * code, no third-party origins: the app uses system fonts and no CDN), and it may not be framed.
     */
    public static final String WEBSITE_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
        + "img-src 'self'; font-src 'self'; connect-src 'self'; manifest-src 'self'; object-src 'none'; "
        + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /** The React web app. Only GET/HEAD, and never a backend path, so it cannot open a way around the other chains. */
    @Bean
    @Order(2)
    @Profile("api")
    SecurityFilterChain websiteSecurityFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher readOnly = new OrRequestMatcher(paths.matcher(HttpMethod.GET, "/**"),
            paths.matcher(HttpMethod.HEAD, "/**"));
        RequestMatcher backend = new OrRequestMatcher(
            WebAppPaths.BACKEND_PATTERNS.stream().map(paths::matcher).toArray(RequestMatcher[]::new));

        http
            .securityMatcher(new AndRequestMatcher(readOnly, new NegatedRequestMatcher(backend)))
            .csrf(csrf -> csrf.disable()) // read-only requests, no session
            .httpBasic(basic -> basic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(WEBSITE_CSP))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .frameOptions(frame -> frame.deny()))
            // X-Content-Type-Options: nosniff is on by default. Spring Security's "no-store" Cache-Control is
            // only added when the response has none, so the resource handlers' cache headers win.
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain apiKeySecurityFilterChain(HttpSecurity http, ApiSecurityProperties props, Environment env)
            throws Exception {
        if (env.acceptsProfiles(Profiles.of("api")) && props.configuredKeys().isEmpty()) {
            throw new IllegalStateException(
                "No API keys configured: set API_KEYS (comma-separated) or security.api-keys");
        }
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> writeProblem(response,
            HttpStatus.UNAUTHORIZED, "Missing or invalid " + ApiKeyAuthenticationFilter.HEADER + " header",
            request.getRequestURI(), null);
        AccessDeniedHandler denied = (request, response, ex) -> writeProblem(response, HttpStatus.FORBIDDEN,
            "Access denied", request.getRequestURI(), null);

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

    /**
     * Machine-readable {@code code} of a CSRF rejection, so clients can recognise it (and fetch a fresh token)
     * without depending on the wording of {@code detail}.
     */
    public static final String CSRF_INVALID_CODE = "csrf_invalid";

    /** {@code detail} and {@code code} are constants here; only {@code instance} comes from the request. */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail, String instance,
            String code) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
            + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\",\"instance\":\""
            + instance.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}");
    }
}
