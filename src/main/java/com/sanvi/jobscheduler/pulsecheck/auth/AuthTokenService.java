package com.sanvi.jobscheduler.pulsecheck.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Single-use, expiring tokens for email links. The raw token (256 random bits, URL-safe) goes only into
 * the email; the database keeps its SHA-256. A plain hash is enough here (unlike passwords) because the
 * token is long and random, so it cannot be guessed from the hash.
 */
@Service
@Profile("api")
@RequiredArgsConstructor
public class AuthTokenService {
    public enum Purpose { EMAIL_VERIFICATION, PASSWORD_RESET }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    /** Creates a token, invalidating any earlier unused token of the same purpose. Returns the raw token. */
    public String issue(UUID userId, Purpose purpose, Duration ttl) {
        invalidateAll(userId, purpose);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("insert into auth_tokens (id, user_id, purpose, token_hash, expires_at) "
            + "values (?, ?, ?, ?, now() + make_interval(secs => ?))",
            UUID.randomUUID(), userId, purpose.name(), hash(token), (double) ttl.toSeconds());
        return token;
    }

    /**
     * Uses a token: succeeds at most once, and only before it expires (database clock). Atomic, so two
     * parallel requests with the same token cannot both succeed.
     *
     * @return the owner's id, or empty if the token is unknown, used, expired or for another purpose
     */
    public Optional<UUID> consume(String rawToken, Purpose purpose) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            return Optional.empty();
        }
        List<UUID> owner = jdbc.queryForList("update auth_tokens set used_at = now() "
            + "where token_hash = ? and purpose = ? and used_at is null and expires_at > now() returning user_id",
            UUID.class, hash(rawToken.trim()), purpose.name());
        return owner.stream().findFirst();
    }

    public void invalidateAll(UUID userId, Purpose purpose) {
        jdbc.update("update auth_tokens set used_at = now() where user_id = ? and purpose = ? and used_at is null",
            userId, purpose.name());
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
