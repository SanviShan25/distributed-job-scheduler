package com.sanvi.jobscheduler.pulsecheck.alert;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sanvi.jobscheduler.pulsecheck.MonitorCheckService;
import com.sanvi.jobscheduler.pulsecheck.RecordingEmailConfiguration;
import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.check.SiteChecker;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.email.EmailSender;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.IncidentRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.MonitorRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

@Import(RecordingEmailConfiguration.class)
class AlertIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER = "owner@example.com";
    private static final HttpChecker.Outcome UP = HttpChecker.Outcome.up(200, 40);
    private static final HttpChecker.Outcome DOWN = HttpChecker.Outcome.down(500, 30, "HTTP 500");

    @Autowired JdbcTemplate jdbc;
    @Autowired MonitorRepository monitors;
    @Autowired CheckResultRepository results;
    @Autowired IncidentRepository incidents;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AlertService alerts;
    @Autowired RecordingEmailConfiguration.RecordingEmailSender emails;

    private UUID ownerId;
    private Monitor monitor;
    private final Deque<HttpChecker.Outcome> script = new ArrayDeque<>();
    private MonitorCheckService checks;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        emails.clear();
        script.clear();
        ownerId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, ?, true)", ownerId, OWNER);
        Instant now = Instant.now();
        monitor = new Monitor();
        monitor.setId(UUID.randomUUID());
        monitor.setUserId(ownerId);
        monitor.setName("Shop");
        monitor.setUrl("https://shop.example.com/");
        monitor.setIntervalSeconds(60);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UP);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        monitors.saveAndFlush(monitor);
        SiteChecker scripted = url -> script.isEmpty() ? UP : script.pop();
        checks = new MonitorCheckService(monitors, results, incidents, scripted, txManager, alerts);
    }

    private void check(HttpChecker.Outcome... outcomes) {
        for (HttpChecker.Outcome outcome : outcomes) {
            script.add(outcome);
            checks.runCheck(monitor.getId());
        }
    }

    private void goDown() {
        check(DOWN, DOWN);
    }

    private List<EmailSender.Email> ownerEmails() {
        return emails.to(OWNER);
    }

    private Map<String, Integer> outcomes() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        jdbc.queryForList("select outcome, count(*) as n from alert_events group by outcome")
            .forEach(row -> counts.put((String) row.get("outcome"), ((Number) row.get("n")).intValue()));
        return counts;
    }

    @Test
    void goingDownQueuesOneDownAlertInTheCheckTransaction() {
        goDown();

        UUID incidentId = jdbc.queryForObject("select id from incidents", UUID.class);
        Map<String, Object> job = jdbc.queryForMap("select * from jobs where type = 'SEND_EMAIL'");
        assertThat(job.get("idempotency_key")).isEqualTo("alert:" + incidentId + ":down");
        assertThat(job.get("status")).as("queued by the check itself, sent later by a worker").isEqualTo("QUEUED");
        assertThat(emails.sentSoFar()).isEmpty();

        List<EmailSender.Email> sent = ownerEmails();
        assertThat(sent).hasSize(1);
        EmailSender.Email email = sent.getFirst();
        assertThat(email.subject()).isEqualTo("Shop is DOWN");
        assertThat(email.text()).contains("https://shop.example.com/").contains("Cause: HTTP 500")
            .contains(" UTC").contains("http://localhost:8080/monitors/" + monitor.getId());
        assertThat(email.html()).contains("Shop is down").contains("href=\"http://localhost:8080/monitors/" + monitor.getId());
    }

    @Test
    void comingBackUpSendsOneUpAlertWithTheDowntime() {
        goDown();
        ownerEmails();
        emails.clear();
        jdbc.update("update incidents set started_at = now() - interval '12 minutes'");

        check(UP);

        List<EmailSender.Email> sent = ownerEmails();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().subject()).isEqualTo("Shop is back UP after 12 minutes");
        assertThat(sent.getFirst().text()).contains("Back up after 12 minutes").contains("cause: HTTP 500");
        UUID incidentId = jdbc.queryForObject("select id from incidents", UUID.class);
        assertThat(jdbc.queryForList("select idempotency_key from jobs where type = 'SEND_EMAIL' order by created_at",
            String.class)).containsExactly("alert:" + incidentId + ":down", "alert:" + incidentId + ":up");
    }

    @Test
    void retriedChecksAndDuplicateTransitionsNeverSendASecondEmail() {
        goDown();
        check(DOWN, DOWN, DOWN); // further failures, or the same check job retried

        UUID incidentId = jdbc.queryForObject("select id from incidents", UUID.class);
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // The same transition delivered again (e.g. a replayed resolution path).
            alerts.incidentOpened(monitor, incidentId, Instant.now(), "HTTP 500");
            alerts.incidentOpened(monitor, incidentId, Instant.now(), "HTTP 500");
        });

        assertThat(ownerEmails()).hasSize(1);
        check(UP, UP);
        assertThat(ownerEmails()).hasSize(2);
    }

    @Test
    void unverifiedOwnersGetNoAlerts() {
        jdbc.update("update users set email_verified = false");

        goDown();
        check(UP);

        assertThat(ownerEmails()).isEmpty();
        assertThat(outcomes()).containsEntry("NO_RECIPIENT", 2);
    }

    @Test
    void flappingIsCappedAtSixEmailsAnHourIncludingThePauseNote() {
        for (int i = 0; i < 4; i++) {  // 8 transitions: D U D U D U D U
            goDown();
            check(UP);
        }

        List<EmailSender.Email> sent = ownerEmails();
        assertThat(sent).hasSize(6);
        assertThat(sent.subList(0, 5)).extracting(EmailSender.Email::subject)
            .containsExactly("Shop is DOWN", "Shop is back UP after less than a minute", "Shop is DOWN",
                "Shop is back UP after less than a minute", "Shop is DOWN");
        assertThat(sent.get(5).subject()).isEqualTo("Alerts paused for Shop for an hour");
        assertThat(sent.get(5).text()).contains("paused alert emails for this monitor for an hour");
        assertThat(outcomes()).containsEntry("SENT", 5).containsEntry("PAUSED", 1).containsEntry("SKIPPED", 2);

        // An hour after the pause note, alerts resume.
        jdbc.update("update alert_events set created_at = created_at - interval '61 minutes'");
        emails.clear();
        goDown();
        assertThat(ownerEmails()).extracting(EmailSender.Email::subject).containsExactly("Shop is DOWN");
    }

    @Test
    void anAlertRolledBackWithItsTransactionIsNeverSent() {
        UUID incidentId = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.update("insert into incidents (id, monitor_id, started_at, cause) values (?, ?, now(), 'HTTP 500')",
                incidentId, monitor.getId());
            alerts.incidentOpened(monitor, incidentId, Instant.now(), "HTTP 500");
            throw new IllegalStateException("the check's transaction fails after the alert was queued");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from jobs where type = 'SEND_EMAIL'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alert_events", Integer.class)).isZero();
        assertThat(ownerEmails()).isEmpty();
    }

    @Test
    void alertsCanOnlyBeQueuedInsideTheIncidentsTransaction() {
        assertThatThrownBy(() -> alerts.incidentOpened(monitor, UUID.randomUUID(), Instant.now(), "HTTP 500"))
            .isInstanceOf(IllegalTransactionStateException.class);
    }
}
