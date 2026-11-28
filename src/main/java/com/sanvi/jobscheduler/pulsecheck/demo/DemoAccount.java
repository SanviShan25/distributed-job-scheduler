package com.sanvi.jobscheduler.pulsecheck.demo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

import com.sanvi.jobscheduler.api.ApiProblems;
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
 * Demo sandboxes: one private, temporary, password-less account per "Try the demo", with sample monitors. Creating
 * them, their restrictions, and deleting them when they expire. Not tied to a profile: the API creates sandboxes,
 * and workers run the cleanup as a {@code DEMO_CLEANUP} job.
 *
 * <p>Everything here writes Postgres only (schedules included), one transaction per operation. The immediate checks
 * of new sample monitors are pushed to Redis after commit. Expiry uses the database clock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoAccount {
    /** Name of the {@code job_schedules} row that runs the cleanup. */
    public static final String CLEANUP_SCHEDULE_NAME = "pulsecheck:demo-cleanup";
    /** Machine-readable {@code code} of a refused demo action, for the web app. */
    public static final String RESTRICTED_CODE = "demo_restricted";
    /** Sandbox addresses are {@code demo-{id}@pulsecheck.invalid}: undeliverable and unownable (RFC 2606). */
    public static final String EMAIL_DOMAIN = "pulsecheck.invalid";
    /** Serializes sandbox creation, so concurrent requests cannot exceed {@code maxSandboxes}. */
    private static final long CAPACITY_LOCK = 0x50554c5345444d4fL; // "PULSEDMO"
    private static final int CLEANUP_BATCH = 100;

    /** The monitors every sandbox starts with. */
    public record SampleMonitor(String name, String url) {}

    public static final List<SampleMonitor> SAMPLE_MONITORS = List.of(
        new SampleMonitor("Example Domain", "https://example.com/"),
        new SampleMonitor("PulseCheck test page",
            "https://raw.githubusercontent.com/SanviShan25/pulsecheck-test/main/status.txt"));

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final CheckTrigger checkTrigger;
    private final DemoProperties demoProps;
    private final PulseCheckProperties props;
    private final SignupProperties signup;
    private final TransactionTemplate tx;

    // ------------------------------------------------------------------ sandboxes

    /**
     * Creates a sandbox: a verified account without a password (the database forbids giving it one), expiring
     * {@code sandboxLifetime} from now, with the sample monitors, each with its schedule and an immediate check.
     *
     * @throws ErrorResponseException 503 "the demo is busy" when {@code maxSandboxes} unexpired sandboxes exist
     */
    @Transactional
    public AppUser createSandbox() {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, CAPACITY_LOCK);
        int active = jdbc.queryForObject("select count(*) from users where demo and demo_expires_at > now()",
            Integer.class);
        if (active >= demoProps.maxSandboxes()) {
            log.info("Demo is full: {} active sandbox(es)", active);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The demo is busy. Please try again in a few minutes.");
            problem.setTitle("The demo is busy");
            problem.setProperty("code", "demo_busy");
            ErrorResponseException busy = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem, null);
            busy.getHeaders().set("Retry-After", "300");
            throw busy;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, created_at, email_verified, failed_login_count, demo, "
                + "demo_expires_at) values (?, ?, null, now(), true, 0, true, now() + make_interval(secs => ?))",
            id, "demo-" + id + "@" + EMAIL_DOMAIN, (double) demoProps.sandboxLifetime().toSeconds());
        for (SampleMonitor sample : SAMPLE_MONITORS) {
            createMonitor(id, sample);
        }
        log.info("Demo sandbox {} created ({} of {})", id, active + 1, demoProps.maxSandboxes());
        return users.findById(id).orElseThrow();
    }

    /** Deletes one sandbox and everything it owns. Does nothing for a normal account. */
    @Transactional
    public void deleteSandbox(UUID userId) {
        jdbc.update("delete from job_schedules where id in (select m.schedule_id from monitors m join users u "
            + "on u.id = m.user_id where u.id = ? and u.demo)", userId);
        if (jdbc.update("delete from users where id = ? and demo", userId) == 1) {
            log.info("Demo sandbox {} deleted", userId);
        }
    }

    /**
     * Deletes expired sandboxes with their monitors, schedules and history (check results, incidents, alert events
     * and recipients cascade from monitors). Monitor schedules are not owned via a foreign key, so they are deleted
     * explicitly. Idempotent: a retried or duplicated run finds nothing left to do.
     *
     * @return the number of sandboxes deleted
     */
    public int deleteExpired() {
        int total = 0;
        while (true) {
            Integer deleted = tx.execute(status -> deleteExpiredBatch());
            total += deleted;
            if (deleted < CLEANUP_BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("Demo cleanup: deleted {} expired sandbox(es)", total);
        }
        return total;
    }

    /** One batch, in its own transaction; SKIP LOCKED lets parallel cleanups split the work. */
    private int deleteExpiredBatch() {
        List<UUID> expired = jdbc.queryForList("select id from users where demo and demo_expires_at <= now() "
            + "order by demo_expires_at limit ? for update skip locked", UUID.class, CLEANUP_BATCH);
        for (UUID id : expired) {
            deleteSandbox(id);
        }
        return expired.size();
    }

    /** Creates or updates the recurring {@code DEMO_CLEANUP} schedule (a changed cron applies at next startup). */
    @Transactional
    public void ensureCleanupSchedule() {
        String cron = CronSupport.normalize(demoProps.cleanupCron());
        Instant next = CronSupport.next(cron, "UTC", Instant.now());
        jdbc.update("""
            insert into job_schedules (id, name, cron_expression, timezone, type, payload, priority, max_attempts,
                                       timeout_seconds, enabled, next_run_at, created_at, updated_at)
            values (?, ?, ?, 'UTC', ?, null, 5, 3, 120, true, ?, now(), now())
            on conflict (name) do update set cron_expression = excluded.cron_expression,
                next_run_at = excluded.next_run_at, enabled = true, updated_at = now()
            where job_schedules.cron_expression <> excluded.cron_expression or not job_schedules.enabled
            """, UUID.randomUUID(), CLEANUP_SCHEDULE_NAME, cron, DemoCleanupJobHandler.TYPE, Timestamp.from(next));
    }

    /** Demo mode is off: deletes every sandbox (expired or not) and the cleanup schedule. */
    @Transactional
    public void removeAll() {
        int schedule = jdbc.update("delete from job_schedules where name = ?", CLEANUP_SCHEDULE_NAME);
        jdbc.update("delete from job_schedules where id in (select m.schedule_id from monitors m join users u "
            + "on u.id = m.user_id where u.demo)");
        int sandboxes = jdbc.update("delete from users where demo");
        if (schedule + sandboxes > 0) {
            log.info("Demo mode is off: removed {} sandbox(es) and the demo cleanup schedule", sandboxes);
        }
    }

    private void createMonitor(UUID userId, SampleMonitor sample) {
        UUID monitorId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        int interval = demoProps.minIntervalSeconds();
        String cron = CronSupport.normalize(MonitorSchedules.cronFor(monitorId, interval));
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
            """, monitorId, userId, sample.name(), sample.url(), interval, scheduleId, now, now);
        checkTrigger.checkNow(monitorId);
    }

    // ------------------------------------------------------------------ lookups and limits

    public boolean isDemo(UUID userId) {
        return Boolean.TRUE.equals(jdbc.query("select demo from users where id = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null, userId));
    }

    /** True if {@code email} belongs to a demo sandbox. Nothing is ever emailed to one. */
    public boolean isDemoAddress(String email) {
        return email != null && Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists (select 1 from users where demo and lower(email) = lower(?))", Boolean.class, email));
    }

    public int monitorLimit(AppUser user) {
        return user.isDemo() ? demoProps.maxMonitors() : props.maxMonitorsPerUser();
    }

    /** Shortest check interval this account may use. */
    public int minIntervalSeconds(AppUser user) {
        return user.isDemo() ? demoProps.minIntervalSeconds() : 60;
    }

    public void checkInterval(AppUser user, int intervalSeconds) {
        if (intervalSeconds < minIntervalSeconds(user)) {
            throw ApiProblems.badRequest("In the demo, websites can be checked at most every "
                + demoProps.minIntervalSeconds() / 60 + " minutes.");
        }
    }

    // ------------------------------------------------------------------ restrictions

    /** Refuses {@code action} for a demo sandbox with 403 and {@code "code": "demo_restricted"}. */
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
