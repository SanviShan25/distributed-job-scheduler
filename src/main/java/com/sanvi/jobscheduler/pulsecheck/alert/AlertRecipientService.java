package com.sanvi.jobscheduler.pulsecheck.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.pulsecheck.auth.EmailAddresses;
import com.sanvi.jobscheduler.pulsecheck.email.EmailJob;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;
import com.sanvi.jobscheduler.security.RedisRateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Extra alert recipients, with double opt-in: an address added by a monitor's owner is PENDING and gets
 * nothing but one confirmation email until its owner clicks the link (CONFIRMED). Anyone can leave a
 * monitor's alerts with the unsubscribe link in every alert (UNSUBSCRIBED), which affects only that address
 * for that monitor.
 *
 * <p>Abuse limits on confirmation emails (Redis buckets, shared by all API instances, failing closed): per
 * account per hour, and per address per day across all accounts, so nobody can use PulseCheck to flood an
 * inbox with confirmation requests.
 */
@Service
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class AlertRecipientService {
    public enum Status { PENDING, CONFIRMED, UNSUBSCRIBED }

    public record Recipient(UUID id, String email, Status status, Instant createdAt, Instant confirmedAt,
        Instant unsubscribedAt) {}

    /** What a confirm/unsubscribe link did, for the public page. */
    public enum LinkResult { DONE, ALREADY_DONE, INVALID, USED }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final RecipientTokens tokens;
    private final EmailOutbox outbox;
    private final AlertProperties props;
    private final RedisRateLimiter limiter;

    private record OwnedMonitor(UUID id, String name, String url, UUID ownerId, String ownerEmail) {}

    // ------------------------------------------------------------------ owner API

    public List<Recipient> list(UUID monitorId) {
        return listFor(List.of(monitorId)).getOrDefault(monitorId, List.of());
    }

    /** Recipients of several monitors in one query, oldest first. */
    public Map<UUID, List<Recipient>> listFor(Collection<UUID> monitorIds) {
        Map<UUID, List<Recipient>> byMonitor = new LinkedHashMap<>();
        if (monitorIds.isEmpty()) {
            return byMonitor;
        }
        named.query("select * from alert_recipients where monitor_id in (:ids) order by created_at, email",
            new MapSqlParameterSource("ids", monitorIds), rs -> {
                byMonitor.computeIfAbsent(rs.getObject("monitor_id", UUID.class), id -> new ArrayList<>())
                    .add(new Recipient(rs.getObject("id", UUID.class), rs.getString("email"),
                        Status.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "confirmed_at"),
                        instant(rs, "unsubscribed_at")));
            });
        return byMonitor;
    }

    /** Adds an address as PENDING and emails it a confirmation link. Re-adding an unsubscribed address asks again. */
    @Transactional
    public Recipient add(UUID ownerId, UUID monitorId, String rawEmail) {
        OwnedMonitor monitor = lockOwnedMonitor(ownerId, monitorId);
        String email = EmailAddresses.normalize(rawEmail);
        if (!EmailAddresses.isValid(email)) {
            throw ApiProblems.badRequest("Please enter a valid email address");
        }
        if (email.equalsIgnoreCase(monitor.ownerEmail())) {
            throw ApiProblems.badRequest("That's your account's address: you already get this monitor's alerts "
                + "(see notifyOwner)");
        }
        Recipient existing = find(monitorId, email);
        if (existing != null && existing.status() != Status.UNSUBSCRIBED) {
            throw ApiProblems.conflict(email + " is already a recipient (" + existing.status() + ")");
        }
        int active = jdbc.queryForObject("select count(*) from alert_recipients where monitor_id = ? "
            + "and status in ('PENDING', 'CONFIRMED')", Integer.class, monitorId);
        if (active >= props.maxExtraRecipients()) {
            throw ApiProblems.conflict("A monitor can have at most " + props.maxExtraRecipients() + " extra recipients");
        }
        checkConfirmationLimits(ownerId, email);

        UUID recipientId;
        if (existing == null) {
            recipientId = UUID.randomUUID();
            jdbc.update("insert into alert_recipients (id, monitor_id, email, status) values (?, ?, ?, 'PENDING')",
                recipientId, monitorId, email);
        } else {
            recipientId = existing.id();
            jdbc.update("update alert_recipients set status = 'PENDING', confirmed_at = null, unsubscribed_at = null "
                + "where id = ?", recipientId);
        }
        sendConfirmation(monitor, recipientId, email);
        return find(monitorId, email);
    }

    /** Sends a PENDING recipient a fresh confirmation link (earlier links stop working). */
    @Transactional
    public Recipient resendConfirmation(UUID ownerId, UUID monitorId, UUID recipientId) {
        OwnedMonitor monitor = lockOwnedMonitor(ownerId, monitorId);
        Recipient recipient = get(monitorId, recipientId);
        if (recipient.status() != Status.PENDING) {
            throw ApiProblems.conflict("Only pending recipients can be sent a confirmation (this one is "
                + recipient.status() + ")");
        }
        checkConfirmationLimits(ownerId, recipient.email());
        sendConfirmation(monitor, recipientId, recipient.email());
        return recipient;
    }

    @Transactional
    public void remove(UUID ownerId, UUID monitorId, UUID recipientId) {
        lockOwnedMonitor(ownerId, monitorId);
        get(monitorId, recipientId);
        jdbc.update("delete from alert_recipients where id = ?", recipientId);
    }

    // ------------------------------------------------------------------ public links (no login)

    /** Confirms a PENDING recipient. Each confirmation link works once. */
    @Transactional
    public LinkResult confirm(String token) {
        var target = tokens.consume(token, RecipientTokens.Purpose.CONFIRM);
        if (target.isEmpty()) {
            return tokens.find(token, RecipientTokens.Purpose.CONFIRM).isPresent() ? LinkResult.USED : LinkResult.INVALID;
        }
        int changed = jdbc.update("update alert_recipients set status = 'CONFIRMED', confirmed_at = now() "
            + "where id = ? and status = 'PENDING'", target.get().recipientId());
        log.info("Alert recipient {} of monitor {} {}", target.get().recipientId(), target.get().monitorId(),
            changed == 1 ? "confirmed" : "not confirmed (no longer pending)");
        return changed == 1 ? LinkResult.DONE : LinkResult.INVALID;
    }

    /**
     * Unsubscribes the link's recipient (or the owner) from this one monitor. Idempotent: using the same link
     * again reports success without changing anything. A link is single-use, so after re-subscribing an old
     * link no longer works.
     */
    @Transactional
    public LinkResult unsubscribe(String token) {
        var consumed = tokens.consume(token, RecipientTokens.Purpose.UNSUBSCRIBE);
        if (consumed.isPresent()) {
            RecipientTokens.Target target = consumed.get();
            if (target.recipientId() == null) {
                jdbc.update("update monitors set notify_owner = false, updated_at = now() where id = ?",
                    target.monitorId());
            } else {
                jdbc.update("update alert_recipients set status = 'UNSUBSCRIBED', unsubscribed_at = now() "
                    + "where id = ? and status <> 'UNSUBSCRIBED'", target.recipientId());
            }
            log.info("Unsubscribed {} from alerts for monitor {}",
                target.recipientId() == null ? "the owner" : "recipient " + target.recipientId(), target.monitorId());
            return LinkResult.DONE;
        }
        var used = tokens.find(token, RecipientTokens.Purpose.UNSUBSCRIBE);
        if (used.isEmpty()) {
            return LinkResult.INVALID;
        }
        return stillUnsubscribed(used.get()) ? LinkResult.ALREADY_DONE : LinkResult.USED;
    }

    private boolean stillUnsubscribed(RecipientTokens.Target target) {
        if (target.recipientId() == null) {
            return Boolean.FALSE.equals(jdbc.query("select notify_owner from monitors where id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : null, target.monitorId()));
        }
        // Deleted by the owner after unsubscribing counts as unsubscribed too.
        String status = jdbc.query("select status from alert_recipients where id = ?",
            rs -> rs.next() ? rs.getString(1) : "UNSUBSCRIBED", target.recipientId());
        return "UNSUBSCRIBED".equals(status);
    }

    // ------------------------------------------------------------------ helpers

    private void sendConfirmation(OwnedMonitor monitor, UUID recipientId, String email) {
        tokens.invalidate(recipientId, RecipientTokens.Purpose.CONFIRM);
        RecipientTokens.Issued token = tokens.issue(monitor.id(), recipientId, RecipientTokens.Purpose.CONFIRM,
            props.confirmationTtl());
        outbox.enqueue(new EmailJob(EmailJob.Template.CONFIRM_ALERT_RECIPIENT, email, Map.of(
            "monitorName", monitor.name(), "url", monitor.url(),
            "ttlHours", String.valueOf(props.confirmationTtl().toHours())),
            "email:confirm-recipient:" + token.id(), token.token()));
        log.info("Queued a confirmation email for alert recipient {} of monitor {}", recipientId, monitor.id());
    }

    private void checkConfirmationLimits(UUID ownerId, String email) {
        consume("alert-confirm:owner:" + ownerId, props.confirmationsPerOwnerPerHour(), 3600,
            "Too many confirmation emails from this account");
        consume("alert-confirm:address:" + RecipientTokens.hash(email).substring(0, 32),
            props.confirmationsPerAddressPerDay(), 86_400, "Too many confirmation emails to this address");
    }

    private void consume(String bucket, int limit, int perSeconds, String message) {
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.tryConsume(bucket, limit, (double) limit / perSeconds);
        } catch (RuntimeException e) {
            // Fail closed: these emails go to third parties, so no limiter means no email.
            log.warn("Rate limiter unavailable; refusing to send a confirmation email", e);
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Can't send confirmation emails right now; please try again later"), e);
        }
        if (!decision.allowed()) {
            long retryAfter = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                message + ". Please try again in " + retryAfter + " seconds.");
            problem.setTitle("Too Many Requests");
            ErrorResponseException error = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
            error.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            throw error;
        }
    }

    /** Locks the monitor row so concurrent adds cannot exceed the recipient limit. */
    private OwnedMonitor lockOwnedMonitor(UUID ownerId, UUID monitorId) {
        List<OwnedMonitor> found = jdbc.query("select m.id, m.name, m.url, m.user_id, u.email from monitors m "
                + "join users u on u.id = m.user_id where m.id = ? and m.user_id = ? for update of m",
            (rs, n) -> new OwnedMonitor(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"),
                rs.getObject("user_id", UUID.class), rs.getString("email")),
            monitorId, ownerId);
        if (found.isEmpty()) {
            throw ApiProblems.notFound("Monitor " + monitorId + " not found");
        }
        return found.getFirst();
    }

    private Recipient get(UUID monitorId, UUID recipientId) {
        return list(monitorId).stream().filter(r -> r.id().equals(recipientId)).findFirst()
            .orElseThrow(() -> ApiProblems.notFound("Recipient " + recipientId + " not found"));
    }

    private Recipient find(UUID monitorId, String email) {
        return list(monitorId).stream().filter(r -> r.email().equalsIgnoreCase(email)).findFirst().orElse(null);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
