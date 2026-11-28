package com.sanvi.jobscheduler.pulsecheck.demo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.sanvi.jobscheduler.pulsecheck.CheckTrigger;
import com.sanvi.jobscheduler.pulsecheck.HttpCheckJobHandler;
import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;
import com.sanvi.jobscheduler.pulsecheck.api.MonitorSchedules;
import com.sanvi.jobscheduler.pulsecheck.auth.SignupProperties;
import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;
import com.sanvi.jobscheduler.schedule.CronSupport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The shared demo account: creating it, its restrictions, and the nightly reset. Not tied to a profile: the API
 * creates the account at startup, and workers run the reset as a {@code DEMO_RESET} job.
 *
 * <p>Everything here writes Postgres only (schedules included), in one transaction per operation, so a reset is
 * all-or-nothing. The immediate checks of the sample monitors are pushed to Redis after commit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoAccount {
    /** Name of the {@code job_schedules} row that runs the nightly reset. */
    public static final String RESET_SCHEDULE_NAME = "pulsecheck:demo-reset";
    /** Machine-readable {@code code} of a refused demo action, for the web app. */
    public static final String RESTRICTED_CODE = "demo_restricted";

    /** The monitors the demo account starts with (and gets back every night). */
    public record SampleMonitor(String name, String url) {}

    public static final List<SampleMonitor> SAMPLE_MONITORS = List.of(
        new SampleMonitor("Example Domain", "https://example.com/"),
        new SampleMonitor("PulseCheck test page",
            "https://raw.githubusercontent.com/SanviShan25/pulsecheck-test/main/status.txt"));

    private static final int SAMPLE_INTERVAL_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final CheckTrigger checkTrigger;
    private final DemoProperties demoProps;
    private final PulseCheckProperties props;
    private final SignupProperties signup;

    // ------------------------------------------------------------------ the account

    public Optional<UUID> userId() {
        return jdbc.queryForList("select id from users where demo", UUID.class).stream().findFirst();
    }

    public Optional<AppUser> user() {
        return userId().flatMap(users::findById);
    }

    public boolean isDemo(UUID userId) {
        return Boolean.TRUE.equals(jdbc.query("select demo from users where id = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null, userId));
    }

    /** True if {@code email} is the demo account's address. Nothing is ever emailed to it. */
    public boolean isDemoAddress(String email) {
        return email != null && Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists (select 1 from users where demo and lower(email) = lower(?))", Boolean.class, email));
    }

    public int monitorLimit(AppUser user) {
        return user.isDemo() ? demoProps.maxMonitors() : props.maxMonitorsPerUser();
    }

    /**
     * Creates the demo account if it does not exist: verified, without a password (so nobody can log in to it with
     * one; the database forbids giving it one). Safe to run concurrently from several API instances.
     *
     * @return true if it was created now
     * @throws IllegalStateException if a normal account already uses the configured demo address
     */
    @Transactional
    public boolean ensureExists() {
        int inserted = jdbc.update("insert into users (id, email, password_hash, created_at, email_verified, "
                + "failed_login_count, demo) values (?, lower(?), null, ?, true, 0, true) on conflict do nothing",
            UUID.randomUUID(), demoProps.email(), Timestamp.from(Instant.now()));
        if (userId().isEmpty()) {
            throw new IllegalStateException("Demo mode: " + demoProps.email() + " already belongs to a normal account. "
                + "Set pulsecheck.demo.email to an unused address.");
        }
        if (inserted == 1) {
            log.info("Demo account created");
        }
        return inserted == 1;
    }

    /** True if the demo account exists and has no monitors (e.g. just created). */
    public boolean hasNoMonitors() {
        return userId().map(id -> jdbc.queryForObject("select count(*) from monitors where user_id = ?",
            Integer.class, id) == 0).orElse(false);
    }

    /**
     * Creates or updates the recurring {@code DEMO_RESET} schedule. Changing {@code pulsecheck.demo.reset-cron}
     * takes effect at the next startup.
     */
    @Transactional
    public void ensureResetSchedule() {
        String cron = CronSupport.normalize(demoProps.resetCron());
        Instant next = CronSupport.next(cron, "UTC", Instant.now());
        jdbc.update("""
            insert into job_schedules (id, name, cron_expression, timezone, type, payload, priority, max_attempts,
                                       timeout_seconds, enabled, next_run_at, created_at, updated_at)
            values (?, ?, ?, 'UTC', ?, null, 5, 3, 120, true, ?, now(), now())
            on conflict (name) do update set cron_expression = excluded.cron_expression,
                next_run_at = excluded.next_run_at, enabled = true, updated_at = now()
            where job_schedules.cron_expression <> excluded.cron_expression or not job_schedules.enabled
            """, UUID.randomUUID(), RESET_SCHEDULE_NAME, cron, DemoResetJobHandler.TYPE, Timestamp.from(next));
    }

    /**
     * Demo mode is off: deletes the demo account (with its monitors, their history and schedules) and the reset
     * schedule, if they exist.
     */
    @Transactional
    public void removeAll() {
        int schedule = jdbc.update("delete from job_schedules where name = ?", RESET_SCHEDULE_NAME);
        Optional<UUID> id = userId();
        id.ifPresent(this::deleteMonitors);
        int account = jdbc.update("delete from users where demo");
        if (schedule + account > 0) {
            log.info("Demo mode is off: removed the demo account and its nightly reset");
        }
    }

    // ------------------------------------------------------------------ nightly reset

    /**
     * Deletes the demo account's monitors and everything that hangs off them (check results, incidents, alert
     * events, extra recipients, schedules), then re-creates the sample monitors, each with its schedule and an
     * immediate check. Running it twice gives the same result. No-op if there is no demo account.
     *
     * <p>The account row is locked first, so a reset never interleaves with another reset or with a visitor creating
     * a monitor (which locks the same row).
     *
     * @return the number of sample monitors created
     */
    @Transactional
    public int reset() {
        Optional<UUID> locked = jdbc.queryForList("select id from users where demo for update", UUID.class)
            .stream().findFirst();
        if (locked.isEmpty()) {
            log.info("Demo reset: there is no demo account; nothing to do");
            return 0;
        }
        UUID userId = locked.get();
        int deleted = deleteMonitors(userId);
        for (SampleMonitor sample : SAMPLE_MONITORS) {
            createMonitor(userId, sample);
        }
        log.info("Demo reset: deleted {} monitor(s), created {} sample monitor(s)", deleted, SAMPLE_MONITORS.size());
        return SAMPLE_MONITORS.size();
    }

    /** Monitor schedules are not owned via a foreign key, so they go first; the rest cascades from monitors. */
    private int deleteMonitors(UUID userId) {
        jdbc.update("delete from job_schedules where id in (select schedule_id from monitors where user_id = ?)", userId);
        return jdbc.update("delete from monitors where user_id = ?", userId);
    }

    private void createMonitor(UUID userId, SampleMonitor sample) {
        UUID monitorId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        String cron = CronSupport.normalize(MonitorSchedules.cronFor(monitorId, SAMPLE_INTERVAL_SECONDS));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
            insert into job_schedules (id, name, cron_expression, timezone, type, payload, priority, max_attempts,
                                       timeout_seconds, enabled, next_run_at, created_at, updated_at)
            values (?, ?, ?, 'UTC', ?, ?, 5, ?, ?, true, ?, ?, ?)
            """, scheduleId, MonitorSchedules.scheduleName(monitorId), cron, HttpCheckJobHandler.TYPE,
            monitorId.toString(), CheckTrigger.CHECK_MAX_ATTEMPTS, CheckTrigger.CHECK_TIMEOUT_SECONDS,
            Timestamp.from(CronSupport.next(cron, "UTC", Instant.now())), now, now);
        jdbc.update("""
            insert into monitors (id, user_id, name, url, interval_seconds, enabled, notify_owner, current_status,
                                  consecutive_failures, schedule_id, created_at, updated_at)
            values (?, ?, ?, ?, ?, true, true, 'UNKNOWN', 0, ?, ?, ?)
            """, monitorId, userId, sample.name(), sample.url(), SAMPLE_INTERVAL_SECONDS, scheduleId, now, now);
        checkTrigger.checkNow(monitorId);
    }

    // ------------------------------------------------------------------ restrictions

    /** Refuses {@code action} for the demo account with 403 and {@code "code": "demo_restricted"}. */
    public void refuseIfDemo(UUID userId, String whatTheDemoCantDo) {
        if (isDemo(userId)) {
            throw restricted(whatTheDemoCantDo);
        }
    }

    public ErrorResponseException restricted(String whatTheDemoCantDo) {
        String detail = "The demo account can't " + whatTheDemoCantDo + "."
            + (signup.open() ? " Sign up for your own account to do that." : "");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setTitle("Not available in the demo");
        problem.setProperty("code", RESTRICTED_CODE);
        return new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
    }
}
