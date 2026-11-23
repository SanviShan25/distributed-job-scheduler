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
 * and the email job's idempotency key is {@code alert:{incidentId}:down|up}.
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
        String key = "alert:" + incidentId + ":" + transition.name().toLowerCase();
        List<String> recipients = recipients(monitor);
        if (recipients.isEmpty()) {
            record(monitor, incidentId, transition, "NO_RECIPIENT");
            log.info("No alert for monitor {} ({}): the owner's email is not verified", monitor.getId(), transition);
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
            for (String to : recipients) {
                outbox.enqueue(new EmailJob(EmailJob.Template.ALERTS_PAUSED, to, pausedParams, key, null));
            }
            log.warn("Alerts for monitor {} paused for an hour after {} emails (flapping)", monitor.getId(), max - 1);
            return;
        }

        record(monitor, incidentId, transition, "SENT");
        for (String to : recipients) {
            outbox.enqueue(new EmailJob(template, to, params, key, null));
        }
        log.info("Queued {} alert for monitor {} (incident {})", transition, monitor.getId(), incidentId);
    }

    /** Who receives alerts: the owner, if their email is verified. */
    List<String> recipients(Monitor monitor) {
        return jdbc.queryForList("select email from users where id = ? and email_verified", String.class,
            monitor.getUserId());
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
