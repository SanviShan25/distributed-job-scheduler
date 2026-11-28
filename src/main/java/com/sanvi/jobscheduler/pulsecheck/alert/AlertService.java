package com.sanvi.jobscheduler.pulsecheck.alert;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.email.EmailJob;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Queues DOWN/UP alert emails when an incident opens or resolves.
 *
 * <p>Must be called inside the transaction that opened/resolved the incident (it is {@code MANDATORY}):
 * the alert's email job is written in that same transaction, so an alert cannot be lost if the process
 * dies in between, and cannot be sent for a transition that was rolled back. The caller holds the monitor's
 * row lock, so decisions for one monitor never interleave.
 *
 * <p>Each transition is handled at most once ({@code alert_events} is unique per incident and transition),
 * and the email job's idempotency key is {@code alert:{incidentId}:down|up} for the owner, with
 * {@code :{recipientId}} appended for each confirmed extra recipient (one job per recipient, so one bad address
 * cannot hold up or fail the others).
 *
 * <p>Monitors of the demo account never send alerts: the transition is recorded as {@code DEMO} and nothing is
 * queued.
 *
 * <p>Recipients: the owner (if verified and not unsubscribed from this monitor) and the monitor's CONFIRMED extra
 * recipients. Every alert email carries its own single-use unsubscribe token (in the job's {@code secret},
 * which is redacted once the job finishes).
 *
 * <p>Flapping protection: at most {@code maxEmailsPerMonitorPerHour} alert emails per monitor in any rolling
 * hour. When a transition would exceed that, a single "alerts paused for an hour" note is sent in its place
 * and further transitions are skipped (and logged) until an hour after the note.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertService {
    public enum Transition { DOWN, UP }

    private final JdbcTemplate jdbc;
    private final EmailOutbox outbox;
    private final AlertProperties props;
    private final RecipientTokens tokens;

    /** One alert recipient; {@code recipientId} is null for the monitor's owner. */
    record Recipient(String email, UUID recipientId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void incidentOpened(Monitor monitor, UUID incidentId, Instant downSince, String cause) {
        Map<String, String> params = baseParams(monitor);
        params.put("downSince", downSince.toString());
        params.put("cause", cause == null ? "unknown" : cause);
        dispatch(monitor, incidentId, Transition.DOWN, EmailJob.Template.ALERT_DOWN, params);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incidentResolved(Monitor monitor, UUID incidentId, Instant downSince, Instant upAt, String cause) {
        Map<String, String> params = baseParams(monitor);
        params.put("downSince", downSince.toString());
        params.put("upAt", upAt.toString());
        params.put("cause", cause == null ? "unknown" : cause);
        dispatch(monitor, incidentId, Transition.UP, EmailJob.Template.ALERT_UP, params);
    }

    private void dispatch(Monitor monitor, UUID incidentId, Transition transition, EmailJob.Template template,
            Map<String, String> params) {
        if (alreadyHandled(incidentId, transition)) {
            log.debug("Alert {} for incident {} already handled", transition, incidentId);
            return;
        }
        if (ownedByDemo(monitor)) {
            // Demo mode: the outage shows in the app as usual, but nobody is emailed about it.
            record(monitor, incidentId, transition, "DEMO");
            log.info("No alert for monitor {} ({}): it belongs to the demo account", monitor.getId(), transition);
            return;
        }
        String key = "alert:" + incidentId + ":" + transition.name().toLowerCase();
        List<Recipient> recipients = recipients(monitor);
        if (recipients.isEmpty()) {
            record(monitor, incidentId, transition, "NO_RECIPIENT");
            log.info("No alert for monitor {} ({}): no verified or confirmed recipient", monitor.getId(), transition);
            return;
        }

        if (pausedInLastHour(monitor.getId())) {
            record(monitor, incidentId, transition, "SKIPPED");
            log.info("Alert {} for monitor {} skipped: alerts are paused after too many in the last hour",
                transition, monitor.getId());
            return;
        }
        int max = props.maxEmailsPerMonitorPerHour();
        if (emailsInLastHour(monitor.getId()) >= max - 1) {
            record(monitor, incidentId, transition, "PAUSED");
            Map<String, String> pausedParams = baseParams(monitor);
            pausedParams.put("status", transition.name());
            pausedParams.put("maxPerHour", String.valueOf(max));
            send(monitor, recipients, EmailJob.Template.ALERTS_PAUSED, pausedParams, key);
            log.warn("Alerts for monitor {} paused for an hour after {} emails (flapping)", monitor.getId(), max - 1);
            return;
        }

        record(monitor, incidentId, transition, "SENT");
        send(monitor, recipients, template, params, key);
        log.info("Queued {} alert for monitor {} (incident {}) to {} recipient(s)", transition, monitor.getId(),
            incidentId, recipients.size());
    }

    private void send(Monitor monitor, List<Recipient> recipients, EmailJob.Template template,
            Map<String, String> params, String baseKey) {
        for (Recipient recipient : recipients) {
            String key = recipient.recipientId() == null ? baseKey : baseKey + ":" + recipient.recipientId();
            RecipientTokens.Issued unsubscribe = tokens.issue(monitor.getId(), recipient.recipientId(),
                RecipientTokens.Purpose.UNSUBSCRIBE, null);
            outbox.enqueue(new EmailJob(template, recipient.email(), params, key, unsubscribe.token()));
        }
    }

    /**
     * Who receives alerts: the owner, if their email is verified and they have not unsubscribed from this
     * monitor, then each CONFIRMED extra recipient (never the owner's address twice).
     */
    List<Recipient> recipients(Monitor monitor) {
        List<Recipient> recipients = new java.util.ArrayList<>(jdbc.query("select u.email from monitors m "
                + "join users u on u.id = m.user_id where m.id = ? and m.notify_owner and u.email_verified",
            (rs, n) -> new Recipient(rs.getString("email"), null), monitor.getId()));
        recipients.addAll(jdbc.query("select r.id, r.email from alert_recipients r join monitors m on m.id = r.monitor_id "
                + "join users u on u.id = m.user_id where r.monitor_id = ? and r.status = 'CONFIRMED' "
                + "and lower(r.email) <> lower(u.email) order by r.created_at",
            (rs, n) -> new Recipient(rs.getString("email"), rs.getObject("id", UUID.class)), monitor.getId()));
        return recipients;
    }

    private boolean ownedByDemo(Monitor monitor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from monitors m join users u "
            + "on u.id = m.user_id where m.id = ? and u.demo)", Boolean.class, monitor.getId()));
    }

    private boolean alreadyHandled(UUID incidentId, Transition transition) {
        Integer count = jdbc.queryForObject("select count(*) from alert_events where incident_id = ? and transition = ?",
            Integer.class, incidentId, transition.name());
        return count != null && count > 0;
    }

    private boolean pausedInLastHour(UUID monitorId) {
        Boolean paused = jdbc.queryForObject("select exists (select 1 from alert_events where monitor_id = ? "
            + "and outcome = 'PAUSED' and created_at > now() - interval '1 hour')", Boolean.class, monitorId);
        return Boolean.TRUE.equals(paused);
    }

    /** Alert emails (alerts and pause notes) caused by this monitor in the last hour, database clock. */
    private int emailsInLastHour(UUID monitorId) {
        Integer count = jdbc.queryForObject("select count(*) from alert_events where monitor_id = ? "
            + "and outcome in ('SENT', 'PAUSED') and created_at > now() - interval '1 hour'", Integer.class, monitorId);
        return count == null ? 0 : count;
    }

    private void record(Monitor monitor, UUID incidentId, Transition transition, String outcome) {
        // ON CONFLICT: a concurrent duplicate (not possible under the monitor row lock, but cheap to guard).
        jdbc.update("insert into alert_events (id, monitor_id, incident_id, transition, outcome) values (?, ?, ?, ?, ?) "
            + "on conflict (incident_id, transition) do nothing",
            UUID.randomUUID(), monitor.getId(), incidentId, transition.name(), outcome);
    }

    private static Map<String, String> baseParams(Monitor monitor) {
        Map<String, String> params = new HashMap<>();
        params.put("monitorId", monitor.getId().toString());
        params.put("monitorName", monitor.getName());
        params.put("url", monitor.getUrl());
        return params;
    }
}
