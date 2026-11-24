package com.sanvi.jobscheduler.pulsecheck.alert;

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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Single-use tokens for alert-recipient links: CONFIRM (double opt-in) and UNSUBSCRIBE (one per alert email).
 * The raw token (256 random bits) goes only into the email; the table keeps its SHA-256. Runs on both the
 * API (confirmations) and the worker (alerts), so it has no profile.
 */
@Component
@RequiredArgsConstructor
public class RecipientTokens {
    public enum Purpose { CONFIRM, UNSUBSCRIBE }

    /** The target of a token: a recipient, or the monitor's owner when {@code recipientId} is null. */
    public record Target(UUID tokenId, UUID monitorId, UUID recipientId, boolean alreadyUsed) {}

    public record Issued(UUID id, String token) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    /** @param ttl null for a link that does not expire (unsubscribe links must keep working) */
    public Issued issue(UUID monitorId, UUID recipientId, Purpose purpose, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        if (ttl == null) {
            jdbc.update("insert into alert_recipient_tokens (id, monitor_id, recipient_id, purpose, token_hash) "
                + "values (?, ?, ?, ?, ?)", id, monitorId, recipientId, purpose.name(), hash(token));
        } else {
            jdbc.update("insert into alert_recipient_tokens (id, monitor_id, recipient_id, purpose, token_hash, expires_at) "
                + "values (?, ?, ?, ?, ?, now() + make_interval(secs => ?))",
                id, monitorId, recipientId, purpose.name(), hash(token), (double) ttl.toSeconds());
        }
        return new Issued(id, token);
    }

    /** Invalidates a recipient's unused tokens of one purpose (e.g. older confirmation links). */
    public void invalidate(UUID recipientId, Purpose purpose) {
        jdbc.update("update alert_recipient_tokens set used_at = now() "
            + "where recipient_id = ? and purpose = ? and used_at is null", recipientId, purpose.name());
    }

    /**
     * Uses a token: at most once, before it expires (database clock), atomically.
     *
     * @return the target, or empty if the token is unknown, already used or expired
     */
    public Optional<Target> consume(String rawToken, Purpose purpose) {
        if (!plausible(rawToken)) {
            return Optional.empty();
        }
        return jdbc.query("update alert_recipient_tokens set used_at = now() where token_hash = ? and purpose = ? "
                + "and used_at is null and (expires_at is null or expires_at > now()) "
                + "returning id, monitor_id, recipient_id",
            (rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getObject("monitor_id", UUID.class),
                rs.getObject("recipient_id", UUID.class), false),
            hash(rawToken.trim()), purpose.name()).stream().findFirst();
    }

    /** Looks a token up without using it (for idempotent unsubscribe and for the confirmation pages). */
    public Optional<Target> find(String rawToken, Purpose purpose) {
        if (!plausible(rawToken)) {
            return Optional.empty();
        }
        List<Target> found = jdbc.query("select id, monitor_id, recipient_id, used_at is not null as used "
                + "from alert_recipient_tokens where token_hash = ? and purpose = ?",
            (rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getObject("monitor_id", UUID.class),
                rs.getObject("recipient_id", UUID.class), rs.getBoolean("used")),
            hash(rawToken.trim()), purpose.name());
        return found.stream().findFirst();
    }

    private static boolean plausible(String rawToken) {
        return rawToken != null && !rawToken.isBlank() && rawToken.length() <= 200;
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
