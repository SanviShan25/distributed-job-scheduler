package com.sanvi.jobscheduler.pulsecheck.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.pulsecheck.demo.DemoProperties;
import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
import com.sanvi.jobscheduler.pulsecheck.email.EmailJob;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;

import lombok.extern.slf4j.Slf4j;

/** PulseCheck accounts: sign-up and password authentication. */
@Service
@Profile("api")
@Slf4j
public class AccountService {
    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 128;

    public static final String INVALID_CREDENTIALS = "Invalid email or password";
    /** Machine-readable {@code code} of a sign-up refused because sign-ups are limited. */
    public static final String SIGNUP_CLOSED_CODE = "signup_closed";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppUserRepository users;
    private final AuthProtectionProperties protection;
    private final AuthTokenService tokens;
    private final EmailOutbox outbox;
    private final TransactionTemplate tx;
    private final SignupProperties signupProps;
    private final DemoProperties demoProps;
    /** Compared against when the email is unknown, so both paths cost one full hash check. */
    private final String dummyHash;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AppUserRepository users,
            AuthProtectionProperties protection, AuthTokenService tokens, EmailOutbox outbox, TransactionTemplate tx,
            SignupProperties signupProps, DemoProperties demoProps) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.users = users;
        this.protection = protection;
        this.tokens = tokens;
        this.outbox = outbox;
        this.tx = tx;
        this.signupProps = signupProps;
        this.demoProps = demoProps;
        this.dummyHash = passwordEncoder.encode("dummy password used for constant-time login checks");
    }

    /** What sign-up tells the caller. Identical whether or not the email was already registered. */
    public record SignupResult(String email, boolean emailVerified) {}

    /**
     * Registers an account. If the email (case-insensitively) is already registered, nothing changes and
     * the caller gets exactly the same answer as for a new account, so sign-up cannot be used to find out
     * who has an account. The password is hashed in both cases so the two paths also take similar time.
     * The password is hashed first, outside any transaction (Argon2 is deliberately slow and must not hold
     * a database connection); then the account, its verification token and the email job are written in
     * one short transaction. The email is sent by a worker afterwards.
     *
     * <p>When sign-ups are limited ({@code pulsecheck.signup.allowed-emails}), any other address is refused with
     * the same answer, whether or not it has an account, so this cannot be used to discover accounts either.
     */
    public SignupResult signup(String rawEmail, String password) {
        String email = EmailAddresses.normalize(rawEmail);
        if (!EmailAddresses.isValid(email)) {
            throw ApiProblems.badRequest("Please enter a valid email address");
        }
        if (!signupProps.allows(email)) {
            throw signupClosed();
        }
        validatePassword(password);

        String hash = passwordEncoder.encode(password);
        tx.executeWithoutResult(status -> createAccount(email, hash));
        return new SignupResult(email, false);
    }

    private void createAccount(String email, String hash) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update(
            "insert into users (id, email, password_hash, created_at, email_verified, failed_login_count) "
                + "values (?, ?, ?, ?, false, 0) on conflict ((lower(email))) do nothing",
            id, email, hash, Timestamp.from(Instant.now()));
        if (inserted == 1) {
            log.info("New PulseCheck account created");
            sendVerificationEmail(id, email);
        } else {
            // Same API answer as a new account; only the real owner of the address learns about the attempt.
            log.info("Sign-up for an already registered email; no account created");
            UUID existing = jdbc.queryForObject("select id from users where lower(email) = lower(?)", UUID.class, email);
            // At most one such notice per account per hour, however often someone tries.
            String hour = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC).format(Instant.now());
            outbox.enqueue(new EmailJob(EmailJob.Template.ALREADY_REGISTERED, email, Map.of(),
                "email:already-registered:" + existing + ":" + hour, null));
        }
    }

    /**
     * Issues a fresh 24 h verification link (older links stop working) and queues the email, in one
     * transaction. Idempotency key {@code email:verify:{tokenId}}.
     */
    @Transactional
    public void sendVerificationEmail(UUID userId, String email) {
        AuthTokenService.IssuedToken issued = tokens.issue(userId, AuthTokenService.Purpose.EMAIL_VERIFICATION,
            protection.verificationTokenTtl());
        outbox.enqueue(new EmailJob(EmailJob.Template.VERIFY_EMAIL, email,
            Map.of("ttlHours", String.valueOf(protection.verificationTokenTtl().toHours())),
            "email:verify:" + issued.id(), issued.token()));
    }

    /**
     * Checks an email/password pair.
     * <ul>
     *   <li>Every failure (unknown email, account without a password, wrong password, locked account)
     *       produces the same "Invalid email or password" error, and every path runs exactly one full
     *       hash check, so neither the message nor the timing reveals whether the account exists.</li>
     *   <li>{@code maxFailedLogins} wrong passwords in a row lock the account for {@code lockDuration}
     *       (database clock). While locked, even the right password is refused, and attempts do not extend
     *       the lock. A successful login resets the counter.</li>
     * </ul>
     */
    public AppUser authenticate(String rawEmail, String password) {
        String email = EmailAddresses.normalize(rawEmail);
        AppUser user = email == null ? null : users.findByEmail(email).orElse(null);
        String candidate = password == null ? "" : password;
        if (user == null || user.getPasswordHash() == null) {
            passwordEncoder.matches(candidate, dummyHash);
            throw invalidCredentials();
        }
        boolean passwordMatches = passwordEncoder.matches(candidate, user.getPasswordHash());
        if (isLocked(user)) {
            throw invalidCredentials();
        }
        if (!passwordMatches) {
            recordFailedLogin(user);
            throw invalidCredentials();
        }
        if (user.getFailedLoginCount() > 0 || user.getLockedUntil() != null) {
            jdbc.update("update users set failed_login_count = 0, locked_until = null where id = ?", user.getId());
        }
        return user;
    }

    private boolean isLocked(AppUser user) {
        Boolean locked = jdbc.queryForObject(
            "select coalesce(locked_until > now(), false) from users where id = ?", Boolean.class, user.getId());
        return Boolean.TRUE.equals(locked);
    }

    /** Atomic: concurrent failures cannot under-count. Reaching the limit locks and restarts the count. */
    private void recordFailedLogin(AppUser user) {
        int max = protection.maxFailedLogins();
        Boolean nowLocked = jdbc.queryForObject("""
            update users set
              locked_until = case when failed_login_count + 1 >= ? then now() + make_interval(secs => ?)
                                  else locked_until end,
              failed_login_count = case when failed_login_count + 1 >= ? then 0 else failed_login_count + 1 end
            where id = ?
            returning locked_until > now()
            """, Boolean.class, max, (double) protection.lockDuration().toSeconds(), max, user.getId());
        if (Boolean.TRUE.equals(nowLocked)) {
            log.warn("Account {} locked for {} after {} failed logins", user.getId(), protection.lockDuration(), max);
        }
    }

    private ErrorResponseException signupClosed() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
            "Sign-ups are limited on this site, and this email address isn't on the list."
                + (demoProps.enabled() ? " You can try the demo account instead." : ""));
        problem.setTitle("Sign-ups are limited");
        problem.setProperty("code", SIGNUP_CLOSED_CODE);
        return new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
    }

    public static ErrorResponseException invalidCredentials() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS);
        problem.setTitle("Unauthorized");
        return new ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null);
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw ApiProblems.badRequest("Password must be between " + MIN_PASSWORD_LENGTH + " and "
                + MAX_PASSWORD_LENGTH + " characters");
        }
    }
}
