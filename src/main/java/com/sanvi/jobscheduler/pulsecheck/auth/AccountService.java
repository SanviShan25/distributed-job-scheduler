package com.sanvi.jobscheduler.pulsecheck.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
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

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AppUserRepository users;
    /** Compared against when the email is unknown, so both paths cost one full hash check. */
    private final String dummyHash;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AppUserRepository users) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.users = users;
        this.dummyHash = passwordEncoder.encode("dummy password used for constant-time login checks");
    }

    /** What sign-up tells the caller. Identical whether or not the email was already registered. */
    public record SignupResult(String email, boolean emailVerified) {}

    /**
     * Registers an account. If the email (case-insensitively) is already registered, nothing changes and
     * the caller gets exactly the same answer as for a new account, so sign-up cannot be used to find out
     * who has an account. The password is hashed in both cases so the two paths also take similar time.
     */
    public SignupResult signup(String rawEmail, String password) {
        String email = EmailAddresses.normalize(rawEmail);
        if (!EmailAddresses.isValid(email)) {
            throw ApiProblems.badRequest("Please enter a valid email address");
        }
        validatePassword(password);

        String hash = passwordEncoder.encode(password);
        int inserted = jdbc.update(
            "insert into users (id, email, password_hash, created_at, email_verified, failed_login_count) "
                + "values (?, ?, ?, ?, false, 0) on conflict ((lower(email))) do nothing",
            UUID.randomUUID(), email, hash, Timestamp.from(Instant.now()));
        if (inserted == 1) {
            log.info("New PulseCheck account created");
        } else {
            log.info("Sign-up for an already registered email; no account created");
        }
        return new SignupResult(email, false);
    }

    /**
     * Checks an email/password pair. Every failure (unknown email, account without a password, wrong
     * password) produces the same "Invalid email or password" error, and unknown emails still run a full
     * hash comparison so the response time does not reveal whether the account exists.
     */
    public AppUser authenticate(String rawEmail, String password) {
        String email = EmailAddresses.normalize(rawEmail);
        AppUser user = email == null ? null : users.findByEmail(email).orElse(null);
        String candidate = password == null ? "" : password;
        if (user == null || user.getPasswordHash() == null) {
            passwordEncoder.matches(candidate, dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(candidate, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return user;
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
