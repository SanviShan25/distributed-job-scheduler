package com.sanvi.jobscheduler.pulsecheck;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;
import com.sanvi.jobscheduler.pulsecheck.domain.Incident;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.IncidentRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.MonitorRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

/** V5 migration: tables, indexes and constraints, plus the JPA mappings (validated at startup). */
class PulseCheckSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired AppUserRepository users;
    @Autowired MonitorRepository monitors;
    @Autowired CheckResultRepository checks;
    @Autowired IncidentRepository incidents;

    @BeforeEach
    void cleanPulseCheckTables() {
        jdbc.update("delete from users"); // cascades to monitors, check_results, incidents
    }

    private AppUser user(String email) {
        AppUser user = new AppUser();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setCreatedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private Monitor monitor(AppUser owner, String url) {
        Instant now = Instant.now();
        Monitor m = new Monitor();
        m.setId(UUID.randomUUID());
        m.setUserId(owner.getId());
        m.setName("Example");
        m.setUrl(url);
        m.setIntervalSeconds(300);
        m.setEnabled(true);
        m.setCurrentStatus(MonitorStatus.UNKNOWN);
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return monitors.saveAndFlush(m);
    }

    private Incident incident(Monitor m, Instant resolvedAt) {
        Incident i = new Incident();
        i.setId(UUID.randomUUID());
        i.setMonitorId(m.getId());
        i.setStartedAt(Instant.now().minusSeconds(60));
        i.setResolvedAt(resolvedAt);
        i.setCause("HTTP 500");
        return i;
    }

    @Test
    void entitiesRoundTrip() {
        AppUser owner = user("owner@example.com");
        Monitor m = monitor(owner, "https://example.com");

        CheckResult result = new CheckResult();
        result.setMonitorId(m.getId());
        result.setCheckedAt(Instant.now());
        result.setUp(false);
        result.setStatusCode(503);
        result.setResponseMs(120);
        result.setError("HTTP 503");
        CheckResult saved = checks.saveAndFlush(result);
        incidents.saveAndFlush(incident(m, null));

        assertThat(saved.getId()).as("database-generated id").isNotNull();
        Monitor reloaded = monitors.findById(m.getId()).orElseThrow();
        assertThat(reloaded.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(reloaded.getUserId()).isEqualTo(owner.getId());
        assertThat(checks.findById(saved.getId()).orElseThrow().getStatusCode()).isEqualTo(503);
        assertThat(incidents.count()).isEqualTo(1);
    }

    @Test
    void emailIsUnique() {
        user("dup@example.com");
        assertThatThrownBy(() -> user("dup@example.com")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void monitorMustBelongToAnExistingUser() {
        AppUser ghost = new AppUser();
        ghost.setId(UUID.randomUUID());
        assertThatThrownBy(() -> monitor(ghost, "https://example.com"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameUrlTwiceForOneUserIsRejectedButAllowedForAnotherUser() {
        AppUser alice = user("alice@example.com");
        AppUser bob = user("bob@example.com");
        monitor(alice, "https://example.com");

        assertThatThrownBy(() -> monitor(alice, "https://example.com"))
            .isInstanceOf(DataIntegrityViolationException.class);
        monitor(bob, "https://example.com");
    }

    @Test
    void statusAndIntervalCheckConstraintsMatchTheModel() {
        AppUser owner = user("c@example.com");
        for (MonitorStatus status : MonitorStatus.values()) {
            Monitor m = monitor(owner, "https://example.com/" + status);
            m.setCurrentStatus(status);
            monitors.saveAndFlush(m);
        }
        assertThatThrownBy(() -> jdbc.update(insertMonitor("BROKEN", 300), UUID.randomUUID(), owner.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insertMonitor("UP", 30), UUID.randomUUID(), owner.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
        for (int allowed : List.of(60, 300, 900, 3600)) {
            jdbc.update(insertMonitor("UP", allowed), UUID.randomUUID(), owner.getId());
        }
    }

    private static String insertMonitor(String status, int interval) {
        return "insert into monitors (id, user_id, name, url, interval_seconds, current_status) values (?, ?, 'x', "
            + "'https://x.example/" + UUID.randomUUID() + "', " + interval + ", '" + status + "')";
    }

    @Test
    void onlyOneOpenIncidentPerMonitor() {
        Monitor m = monitor(user("i@example.com"), "https://example.com");
        incidents.saveAndFlush(incident(m, null));

        assertThatThrownBy(() -> incidents.saveAndFlush(incident(m, null)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolvedIncidentsDoNotBlockANewOpenOne() {
        Monitor m = monitor(user("r@example.com"), "https://example.com");
        incidents.saveAndFlush(incident(m, Instant.now().minusSeconds(30)));
        incidents.saveAndFlush(incident(m, Instant.now().minusSeconds(10)));
        incidents.saveAndFlush(incident(m, null));

        assertThat(incidents.count()).isEqualTo(3);
    }

    @Test
    void openIncidentsOfDifferentMonitorsAreIndependent() {
        AppUser owner = user("d@example.com");
        incidents.saveAndFlush(incident(monitor(owner, "https://a.example"), null));
        incidents.saveAndFlush(incident(monitor(owner, "https://b.example"), null));
        assertThat(incidents.count()).isEqualTo(2);
    }

    @Test
    void deletingAMonitorDeletesItsHistory() {
        Monitor m = monitor(user("h@example.com"), "https://example.com");
        jdbc.update("insert into check_results (monitor_id, checked_at, up) values (?, now(), true)", m.getId());
        incidents.saveAndFlush(incident(m, null));

        monitors.deleteById(m.getId());
        monitors.flush();

        assertThat(checks.count()).isZero();
        assertThat(incidents.count()).isZero();
    }

    @Test
    void indexesExist() {
        List<String> defs = jdbc.queryForList(
            "select indexdef from pg_indexes where tablename in ('check_results', 'incidents')", String.class);
        assertThat(defs).anyMatch(d -> d.contains("(monitor_id, checked_at DESC)"));
        assertThat(defs).anyMatch(d -> d.contains("UNIQUE") && d.contains("(monitor_id) WHERE (resolved_at IS NULL)"));
    }

    @Test
    void devUserSeederIsIdempotent() {
        DevUserSeeder seeder = new DevUserSeeder(jdbc, new PulseCheckProperties("seed@pulsecheck.local", PulseCheckProperties.Checker.defaults()));
        seeder.run(null);
        seeder.run(null);

        assertThat(users.findByEmail("seed@pulsecheck.local")).isPresent();
        assertThat(users.count()).isEqualTo(1);
    }
}
