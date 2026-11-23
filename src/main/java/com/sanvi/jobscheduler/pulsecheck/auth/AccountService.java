package com.sanvi.jobscheduler.pulsecheck.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.sanvi.jobscheduler.api.ApiProblems;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** PulseCheck accounts: sign-up (more account operations follow in later steps). */
@Service
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class AccountService {
    public static final int MIN_PASSWORD_LENGTH = 10;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

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

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw ApiProblems.badRequest("Password must be between " + MIN_PASSWORD_LENGTH + " and "
                + MAX_PASSWORD_LENGTH + " characters");
        }
    }
}
