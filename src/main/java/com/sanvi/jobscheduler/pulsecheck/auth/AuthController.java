package com.sanvi.jobscheduler.pulsecheck.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;
import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * PulseCheck account endpoints. Sessions live in Redis (Spring Session); every state-changing request
 * needs the CSRF token (see {@link #csrf}).
 */
@RestController
@RequestMapping("/api/auth")
@Profile("api")
@RequiredArgsConstructor
public class AuthController {
    private final AccountService accounts;
    private final AppUserRepository users;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final PulseCheckProperties props;

    public record SignupRequest(@NotNull String email, @NotNull String password) {}

    /** The new account. Deliberately the same for an email that is already registered. */
    public record SignupResponse(String email, boolean emailVerified, String message) {}

    public record LoginRequest(@NotNull String email, @NotNull String password, Boolean rememberMe) {}

    public record MeResponse(UUID id, String email, boolean emailVerified, Instant createdAt) {
        static MeResponse of(AppUser user) {
            return new MeResponse(user.getId(), user.getEmail(), user.isEmailVerified(), user.getCreatedAt());
        }
    }

    /** Where to put the CSRF token on state-changing requests. */
    public record CsrfResponse(String headerName, String cookieName, String token) {}

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
        AccountService.SignupResult result = accounts.signup(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(result.email(),
            result.emailVerified(), "Account created. Check your inbox to verify your email address."));
    }

    /**
     * Logs in and starts a session. The session id is always rotated (an existing session gets a new id,
     * so a pre-login id planted by an attacker is useless) and a new CSRF token is issued.
     */
    @PostMapping("/login")
    public MeResponse login(@RequestBody @Valid LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AppUser user = accounts.authenticate(request.email(), request.password());
        boolean rememberMe = Boolean.TRUE.equals(request.rememberMe());
        if (rememberMe) {
            httpRequest.setAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR, true);
        }

        if (httpRequest.getSession(false) != null) {
            httpRequest.changeSessionId();
        }
        HttpSession session = httpRequest.getSession(true);
        PulseCheckProperties.Session cfg = props.session();
        session.setMaxInactiveInterval((int) (rememberMe ? cfg.rememberMeTimeout() : cfg.idleTimeout()).toSeconds());

        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            new SessionUser(user.getId(), user.getEmail()), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);
        rotateCsrfToken(httpRequest, httpResponse);
        return MeResponse.of(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        rotateCsrfToken(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal SessionUser principal, HttpServletRequest request) {
        AppUser user = users.findById(principal.id()).orElse(null);
        if (user == null) {
            // The account was deleted while this session was alive.
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            throw AccountService.invalidCredentials();
        }
        return MeResponse.of(user);
    }

    /**
     * Issues (or returns) the CSRF token. A browser app calls this once on start-up and after login/logout,
     * then sends the token in the {@code X-XSRF-TOKEN} header on every POST/PATCH/PUT/DELETE. The same value
     * is in the readable {@code XSRF-TOKEN} cookie, so the app may read it from there instead.
     */
    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(token, request, response);
        }
        return new CsrfResponse(token.getHeaderName(), "XSRF-TOKEN", token.getToken());
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }
}
