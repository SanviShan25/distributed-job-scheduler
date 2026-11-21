package com.sanvi.jobscheduler.pulsecheck;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.check.SiteChecker;
import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.IncidentRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.MonitorRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs one check of a monitor and records it.
 *
 * <ol>
 *   <li>Load the monitor (no transaction). Deleted or paused: nothing to do.</li>
 *   <li>Run the HTTP check <em>outside</em> any transaction, so a slow site never holds a DB connection.</li>
 *   <li>In one transaction, lock the monitor row, re-check it still exists and is enabled, store the
 *       result, update the status and open/resolve the incident.</li>
 * </ol>
 *
 * <p>Status rules: DOWN only after {@value #FAILURES_FOR_DOWN} consecutive failures; UP again after one
 * success. A single failure leaves an UP monitor UP and an UNKNOWN monitor UNKNOWN.
 *
 * <p>Idempotency: opening an incident is {@code INSERT … ON CONFLICT DO NOTHING} on the one-open-incident
 * partial unique index, and resolving is a conditional UPDATE, so a retried job (or two overlapping checks)
 * can never create a second incident. A website being down is a normal result: only unexpected errors
 * (e.g. the database is unreachable) propagate, which makes the job retry.
 */
@Service
@Slf4j
public class MonitorCheckService {
    public static final int FAILURES_FOR_DOWN = 2;

    /** What happened; {@code checked} is false when the monitor was deleted or paused. */
    public record Result(boolean checked, MonitorStatus status, boolean incidentOpened, boolean incidentResolved) {
        static Result skipped() {
            return new Result(false, null, false, false);
        }
    }

    private final MonitorRepository monitors;
    private final CheckResultRepository results;
    private final IncidentRepository incidents;
    private final SiteChecker checker;
    private final TransactionTemplate tx;

    public MonitorCheckService(MonitorRepository monitors, CheckResultRepository results, IncidentRepository incidents,
            SiteChecker checker, PlatformTransactionManager txManager) {
        this.monitors = monitors;
        this.results = results;
        this.incidents = incidents;
        this.checker = checker;
        this.tx = new TransactionTemplate(txManager);
    }

    public Result runCheck(UUID monitorId) {
        Monitor monitor = monitors.findById(monitorId).orElse(null);
        if (monitor == null || !monitor.isEnabled()) {
            log.debug("Skipping check of monitor {}: {}", monitorId, monitor == null ? "deleted" : "paused");
            return Result.skipped();
        }
        HttpChecker.Outcome outcome = checker.check(monitor.getUrl());
        Instant checkedAt = Instant.now();
        return tx.execute(status -> record(monitorId, outcome, checkedAt));
    }

    private Result record(UUID monitorId, HttpChecker.Outcome outcome, Instant checkedAt) {
        Monitor monitor = monitors.findByIdForUpdate(monitorId).orElse(null);
        if (monitor == null || !monitor.isEnabled()) {
            // Deleted or paused while the check was in flight: discard the result.
            return Result.skipped();
        }

        CheckResult result = new CheckResult();
        result.setMonitorId(monitorId);
        result.setCheckedAt(checkedAt);
        result.setUp(outcome.up());
        result.setStatusCode(outcome.statusCode());
        result.setResponseMs(outcome.responseMs());
        result.setError(outcome.error());
        results.save(result);

        MonitorStatus previous = monitor.getCurrentStatus();
        int failures = outcome.up() ? 0 : monitor.getConsecutiveFailures() + 1;
        MonitorStatus next = nextStatus(previous, outcome.up(), failures);
        monitor.setConsecutiveFailures(failures);
        monitor.setCurrentStatus(next);
        monitor.setLastCheckedAt(checkedAt);
        monitor.setUpdatedAt(checkedAt);
        monitors.save(monitor);

        boolean opened = false;
        boolean resolved = false;
        if (next == MonitorStatus.DOWN) {
            results.flush();
            Instant startedAt = results.streakStartedAt(monitorId, failures);
            opened = incidents.openIfNoneOpen(UUID.randomUUID(), monitorId,
                startedAt == null ? checkedAt : startedAt, outcome.error()) == 1;
        } else if (next == MonitorStatus.UP) {
            resolved = incidents.resolveOpen(monitorId, checkedAt) == 1;
        }

        if (previous != next) {
            log.info("Monitor {} ({}) is now {} (was {}){}", monitorId, monitor.getUrl(), next, previous,
                outcome.up() ? "" : ": " + outcome.error());
        }
        return new Result(true, next, opened, resolved);
    }

    static MonitorStatus nextStatus(MonitorStatus previous, boolean up, int consecutiveFailures) {
        if (up) {
            return MonitorStatus.UP;
        }
        if (consecutiveFailures >= FAILURES_FOR_DOWN) {
            return MonitorStatus.DOWN;
        }
        // A single failure is not enough to declare DOWN; keep what we knew.
        return previous == MonitorStatus.PAUSED ? MonitorStatus.UNKNOWN : previous;
    }
}
