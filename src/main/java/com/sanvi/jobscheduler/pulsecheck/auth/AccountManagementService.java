package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
import com.sanvi.jobscheduler.pulsecheck.email.EmailJob;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Email verification, password reset/change and account deletion. Session invalidation uses Spring
 * Session's per-user index (the principal name is the user id).
 */
@Service
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class AccountManagementService {
    public static final String INVALID_LINK = "This link is invalid or has expired. Please request a new one.";

    private final AppUserRepository users;
    private final AccountService accounts;
    private final AuthTokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final EmailOutbox outbox;
    private final AuthProtectionProperties protection;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    // ------------------------------------------------------------------ email verification

    @Transactional
    public void verifyEmail(String token) {
        UUID userId = tokens.consume(token, AuthTokenService.Purpose.EMAIL_VERIFICATION)
            .orElseThrow(() -> ApiProblems.badRequest(INVALID_LINK));
        jdbc.update("update users set email_verified = true where id = ?", userId);
        log.info("Email verified for account {}", userId);
    }

    /** @return false if the address was already verified (nothing sent) */
    public boolean resendVerification(UUID userId) {
        AppUser user = existing(userId);
        if (user.isEmailVerified()) {
            return false;
        }
        accounts.sendVerificationEmail(user.getId(), user.getEmail());
        return true;
    }

    // ------------------------------------------------------------------ password reset

    /**
     * Emails a 1 h reset link if the address has an account; does nothing otherwise. The caller answers
     * the same way in both cases, so this cannot be used to discover accounts.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = EmailAddresses.normalize(rawEmail);
        if (!EmailAddresses.isValid(email)) {
            return;
        }
        users.findByEmail(email).ifPresent(user -> {
            AuthTokenService.IssuedToken issued = tokens.issue(user.getId(), AuthTokenService.Purpose.PASSWORD_RESET,
                protection.resetTokenTtl());
            outbox.enqueue(new EmailJob(EmailJob.Template.PASSWORD_RESET, user.getEmail(),
                Map.of("ttlMinutes", String.valueOf(protection.resetTokenTtl().toMinutes())),
                "email:reset:" + issued.id(), issued.token()));
        });
    }

    /**
     * Sets a new password with a reset token. Also unlocks the account, marks the email verified (the user
     * just proved they read it) and logs out every session of the user.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        AccountService.validatePassword(newPassword);
        UUID userId = tokens.consume(token, AuthTokenService.Purpose.PASSWORD_RESET)
            .orElseThrow(() -> ApiProblems.badRequest(INVALID_LINK));
        jdbc.update("update users set password_hash = ?, failed_login_count = 0, locked_until = null, "
            + "email_verified = true where id = ?", passwordEncoder.encode(newPassword), userId);
        tokens.invalidateAll(userId, AuthTokenService.Purpose.PASSWORD_RESET);
        int ended = endSessions(userId, null);
        log.info("Password reset for account {}; {} session(s) ended", userId, ended);
    }

    // ------------------------------------------------------------------ logged-in account changes

    /** Changes the password and logs out every <em>other</em> session; the current one stays. */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String currentSessionId) {
        AppUser user = existing(userId);
        requirePassword(user, currentPassword);
        AccountService.validatePassword(newPassword);
        jdbc.update("update users set password_hash = ? where id = ?", passwordEncoder.encode(newPassword), userId);
        tokens.invalidateAll(userId, AuthTokenService.Purpose.PASSWORD_RESET);
        int ended = endSessions(userId, currentSessionId);
        log.info("Password changed for account {}; {} other session(s) ended", userId, ended);
    }

    /**
     * Deletes the account and everything it owns: monitors (with their check results and incidents, via
     * ON DELETE CASCADE), their recurring schedules, tokens and all sessions. Already-queued checks of the
     * deleted monitors run as no-ops.
     */
    @Transactional
    public void deleteAccount(UUID userId, String password) {
        AppUser user = existing(userId);
        requirePassword(user, password);
        int schedules = jdbc.update(
            "delete from job_schedules where id in (select schedule_id from monitors where user_id = ?)", userId);
        jdbc.update("delete from users where id = ?", userId);
        int ended = endSessions(userId, null);
        log.info("Account {} deleted with {} monitor schedule(s); {} session(s) ended", userId, schedules, ended);
    }

    // ------------------------------------------------------------------ helpers

    /** Deletes the user's sessions except {@code keepSessionId} (may be null). Returns how many ended. */
    int endSessions(UUID userId, String keepSessionId) {
        Set<String> ids = sessions.findByPrincipalName(userId.toString()).keySet();
        int ended = 0;
        for (String id : ids) {
            if (!id.equals(keepSessionId)) {
                sessions.deleteById(id);
                ended++;
            }
        }
        return ended;
    }

    private AppUser existing(UUID userId) {
        return users.findById(userId).orElseThrow(AccountService::invalidCredentials);
    }

    private void requirePassword(AppUser user, String password) {
        if (password == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Your password is incorrect");
            problem.setTitle("Forbidden");
            throw new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
        }
    }
}
