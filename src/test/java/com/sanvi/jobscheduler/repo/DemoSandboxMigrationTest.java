package com.sanvi.jobscheduler.repo;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Upgrading a database that ran V12 (one shared demo account, reset nightly) to the latest schema: V13 must remove
 * the shared account with everything hanging off it, its reset schedule and pending reset jobs, and leave real users'
 * data alone. Uses its own fresh database and plain Flyway (no Spring context, which would migrate to the latest
 * version straight away).
 */
@Testcontainers
class DemoSandboxMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    private static Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(target)
            .load();
    }

    @Test
    void v13RemovesTheSharedDemoAccountAndKeepsRealUsersData() {
        flyway("12").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
            POSTGRES.getPassword()));
        assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class))
            .isEqualTo(12);

        // V12 state: the shared demo account with a monitor, its schedule, history, the nightly reset schedule and
        // a pending reset run (plus a finished one, which is history and stays).
        UUID demo = UUID.randomUUID();
        UUID demoMonitor = UUID.randomUUID();
        UUID demoSchedule = UUID.randomUUID();
        UUID resetSchedule = UUID.randomUUID();
        UUID pendingReset = UUID.randomUUID();
        UUID finishedReset = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified, demo) values (?, 'demo@pulsecheck.invalid', true, true)",
            demo);
        insertMonitorWithSchedule(jdbc, demo, demoMonitor, demoSchedule, "https://example.com/");
        jdbc.update("insert into job_schedules (id, name, cron_expression, type, next_run_at) "
            + "values (?, 'pulsecheck:demo-reset', '0 0 4 * * *', 'DEMO_RESET', now() + interval '1 hour')", resetSchedule);
        jdbc.update("insert into jobs (id, type, status, cron_schedule_id) values (?, 'DEMO_RESET', 'QUEUED', ?)",
            pendingReset, resetSchedule);
        jdbc.update("insert into jobs (id, type, status, completed_at) values (?, 'DEMO_RESET', 'SUCCEEDED', now())",
            finishedReset);

        // A real user with the same kind of data, including a pending check job.
        UUID real = UUID.randomUUID();
        UUID realMonitor = UUID.randomUUID();
        UUID realSchedule = UUID.randomUUID();
        UUID realCheck = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, email_verified) "
            + "values (?, 'ann@example.com', '{argon2}hash', true)", real);
        insertMonitorWithSchedule(jdbc, real, realMonitor, realSchedule, "https://ann.example.com/");
        jdbc.update("insert into jobs (id, type, payload, status, cron_schedule_id) values (?, 'HTTP_CHECK', ?, 'QUEUED', ?)",
            realCheck, realMonitor.toString(), realSchedule);

        flyway("latest").migrate();

        // The shared demo account and everything it owned are gone.
        assertThat(count(jdbc, "select count(*) from users where demo")).isZero();
        assertThat(count(jdbc, "select count(*) from monitors where id = ?", demoMonitor)).isZero();
        assertThat(count(jdbc, "select count(*) from check_results where monitor_id = ?", demoMonitor)).isZero();
        assertThat(count(jdbc, "select count(*) from incidents where monitor_id = ?", demoMonitor)).isZero();
        assertThat(count(jdbc, "select count(*) from job_schedules where id in (?, ?)", demoSchedule, resetSchedule))
            .isZero();
        assertThat(count(jdbc, "select count(*) from jobs where id = ?", pendingReset)).isZero();
        assertThat(count(jdbc, "select count(*) from jobs where id = ?", finishedReset)).isEqualTo(1);

        // The real user's data is untouched.
        assertThat(jdbc.queryForObject("select email from users where id = ?", String.class, real))
            .isEqualTo("ann@example.com");
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, real))
            .isEqualTo("{argon2}hash");
        assertThat(jdbc.queryForObject("select demo_expires_at is null from users where id = ?", Boolean.class, real))
            .isTrue();
        assertThat(jdbc.queryForObject("select schedule_id from monitors where id = ?", UUID.class, realMonitor))
            .isEqualTo(realSchedule);
        assertThat(count(jdbc, "select count(*) from check_results where monitor_id = ?", realMonitor)).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from incidents where monitor_id = ?", realMonitor)).isEqualTo(1);
        assertThat(count(jdbc, "select count(*) from job_schedules where id = ?", realSchedule)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, realCheck))
            .isEqualTo("QUEUED");

        // And the new schema allows many sandboxes, each with an expiry.
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into users (id, email, email_verified, demo, demo_expires_at) "
                + "values (?, ?, true, true, now() + interval '3 hours')", UUID.randomUUID(), "demo-" + i + "@pulsecheck.invalid");
        }
        assertThat(count(jdbc, "select count(*) from users where demo")).isEqualTo(2);
    }

    private static void insertMonitorWithSchedule(JdbcTemplate jdbc, UUID user, UUID monitor, UUID schedule, String url) {
        jdbc.update("insert into job_schedules (id, name, cron_expression, type, payload, next_run_at) "
            + "values (?, ?, '7 * * * * *', 'HTTP_CHECK', ?, now())", schedule, "monitor:" + monitor, monitor.toString());
        jdbc.update("insert into monitors (id, user_id, name, url, interval_seconds, schedule_id) values (?, ?, 'Site', ?, 60, ?)",
            monitor, user, url, schedule);
        jdbc.update("insert into check_results (monitor_id, checked_at, up, status_code, response_ms) "
            + "values (?, now(), false, 500, 40)", monitor);
        jdbc.update("insert into incidents (id, monitor_id, started_at, cause) values (?, ?, now(), 'HTTP 500')",
            UUID.randomUUID(), monitor);
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
