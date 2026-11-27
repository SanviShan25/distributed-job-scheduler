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
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final AuthRateLimiter rateLimiter;
    private final AccountManagementService management;
    private final AppUserRepository users;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final PulseCheckProperties props;

    public record SignupRequest(@NotNull String email, @NotNull String password) {}

    /** The new account. Deliberately the same for an email that is already registered. */
    public record SignupResponse(String email, boolean emailVerified, String message) {}

    public record LoginRequest(@NotNull String email, @NotNull String password, Boolean rememberMe) {}

    /** {@code monitorLimit}: how many monitors this account may have. */
    public record MeResponse(UUID id, String email, boolean emailVerified, Instant createdAt, int monitorLimit) {
        static MeResponse of(AppUser user, int monitorLimit) {
            return new MeResponse(user.getId(), user.getEmail(), user.isEmailVerified(), user.getCreatedAt(),
                monitorLimit);
        }
    }

    public record TokenRequest(@NotNull String token) {}

    public record EmailRequest(@NotNull String email) {}

    public record ResetPasswordRequest(@NotNull String token, @NotNull String newPassword) {}

    public record ChangePasswordRequest(@NotNull String currentPassword, @NotNull String newPassword) {}

    public record PasswordRequest(@NotNull String password) {}

    public record MessageResponse(String message) {}

    /** Where to put the CSRF token on state-changing requests. */
    public record CsrfResponse(String headerName, String cookieName, String token) {}

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody @Valid SignupRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkSignup(clientIp(httpRequest));
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
        rateLimiter.checkLogin(clientIp(httpRequest), EmailAddresses.normalize(request.email()));
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
        return MeResponse.of(user, props.maxMonitorsPerUser());
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

    /**
     * The logged-in user, or 204 No Content when nobody is logged in (no session, an ended session, or an
     * account deleted while its session was alive). Not being logged in is a normal answer to this question,
     * so it is not a 401, which browsers log as an error on every visit by a logged-out user.
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal SessionUser principal, HttpServletRequest request) {
        if (principal == null) {
            return ResponseEntity.noContent().build();
        }
        AppUser user = users.findById(principal.id()).orElse(null);
        if (user == null) {
            // The account was deleted while this session was alive.
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(MeResponse.of(user, props.maxMonitorsPerUser()));
    }

    /** Confirms the email address with the token from the verification link. Works without being logged in. */
    @PostMapping("/verify")
    public MessageResponse verify(@RequestBody @Valid TokenRequest request) {
        management.verifyEmail(request.token());
        return new MessageResponse("Your email address is verified.");
    }

    /** Sends a new verification link to the logged-in user (earlier links stop working). Rate-limited. */
    @PostMapping("/resend-verification")
    public MessageResponse resendVerification(@AuthenticationPrincipal SessionUser principal) {
        rateLimiter.checkResendVerification(principal.id());
        return new MessageResponse(management.resendVerification(principal.id())
            ? "We've sent you a new verification link."
            : "Your email address is already verified.");
    }

    /** Always the same answer, whether or not the address has an account. Rate-limited. */
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@RequestBody @Valid EmailRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkForgotPassword(clientIp(httpRequest), EmailAddresses.normalize(request.email()));
        management.forgotPassword(request.email());
        return ResponseEntity.accepted().body(new MessageResponse(
            "If an account exists for that address, we've emailed a link to reset the password."));
    }

    /** Sets a new password with the token from the reset link, and logs out all of that user's sessions. */
    @PostMapping("/reset-password")
    public MessageResponse resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        management.resetPassword(request.token(), request.newPassword());
        return new MessageResponse("Your password has been changed. Please log in with your new password.");
    }

    /** Needs the current password. Other sessions are logged out; this one stays (with a new id). */
    @PostMapping("/change-password")
    public MessageResponse changePassword(@AuthenticationPrincipal SessionUser principal,
            @RequestBody @Valid ChangePasswordRequest request, HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        // End the other sessions while this one still has its stored id (Spring Session only renames it in
        // Redis when the response is committed), then rotate this session's id.
        management.changePassword(principal.id(), request.currentPassword(), request.newPassword(),
            session == null ? null : session.getId());
        if (session != null) {
            httpRequest.changeSessionId();
        }
        return new MessageResponse("Your password has been changed.");
    }

    /** Deletes the account and all its monitors and data. Needs the password. */
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal SessionUser principal,
            @RequestBody @Valid PasswordRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        management.deleteAccount(principal.id(), request.password());
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        rotateCsrfToken(httpRequest, response);
        return ResponseEntity.noContent().build();
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

    /**
     * The TCP peer address. Forwarded headers are not trusted here: behind a reverse proxy, configure
     * {@code server.forward-headers-strategy} so the container resolves the real client address.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }
}
