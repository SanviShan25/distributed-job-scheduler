package com.sanvi.jobscheduler.pulsecheck.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobSchedule;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.pulsecheck.HttpCheckJobHandler;
import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;
import com.sanvi.jobscheduler.pulsecheck.check.UnsafeUrlException;
import com.sanvi.jobscheduler.pulsecheck.check.UrlSafetyValidator;
import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;
import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;
import com.sanvi.jobscheduler.pulsecheck.domain.Incident;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.IncidentRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.MonitorRepository;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;
import com.sanvi.jobscheduler.schedule.ScheduleService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Monitor lifecycle. A monitor and its recurring {@code HTTP_CHECK} schedule both live in Postgres, so
 * every change to the pair happens in one transaction. The only Redis write, enqueueing the immediate
 * check, runs after commit; if Redis is unavailable then, the job row is already QUEUED in Postgres and
 * the scheduler's drift sweeper enqueues it within about a minute.
 */
@Service
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class MonitorService {
    static final int CHECK_TIMEOUT_SECONDS = 30;
    static final int CHECK_MAX_ATTEMPTS = 3;

    private final MonitorRepository monitors;
    private final AppUserRepository users;
    private final CheckResultRepository results;
    private final IncidentRepository incidents;
    private final ScheduleService schedules;
    private final JobRepository jobs;
    private final JobQueue queue;
    private final UrlSafetyValidator urlValidator;
    private final PulseCheckProperties props;

    public record MonitorChanges(String name, String url, Integer intervalSeconds, Boolean notifyOwner) {}

    @Transactional
    public Monitor create(UUID userId, MonitorChanges request) {
        AppUser owner = users.findByIdForUpdate(userId).orElseThrow(() -> ApiProblems.notFound("User not found"));
        if (!owner.isEmailVerified()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Please verify your email address before creating monitors. "
                    + "Check your inbox for the link, or request a new one (POST /api/auth/resend-verification).");
            problem.setTitle("Email not verified");
            throw new ErrorResponseException(HttpStatus.FORBIDDEN, problem, null);
        }
        if (monitors.countByUserId(userId) >= props.maxMonitorsPerUser()) {
            throw ApiProblems.conflict("You can have at most " + props.maxMonitorsPerUser() + " monitors");
        }
        String url = safeUrl(request.url());
        if (monitors.existsByUserIdAndUrl(userId, url)) {
            throw ApiProblems.conflict("You already have a monitor for " + url);
        }
        int interval = validInterval(request.intervalSeconds());

        Instant now = Instant.now();
        Monitor monitor = new Monitor();
        monitor.setId(UUID.randomUUID());
        monitor.setUserId(userId);
        monitor.setName(validName(request.name()));
        monitor.setUrl(url);
        monitor.setIntervalSeconds(interval);
        monitor.setNotifyOwner(request.notifyOwner() == null || request.notifyOwner());
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);

        JobSchedule schedule = schedules.create(new ScheduleService.ScheduleSpec(
            MonitorSchedules.scheduleName(monitor.getId()), MonitorSchedules.cronFor(monitor.getId(), interval), "UTC",
            HttpCheckJobHandler.TYPE, monitor.getId().toString(), 5, CHECK_MAX_ATTEMPTS, CHECK_TIMEOUT_SECONDS, true));
        monitor.setScheduleId(schedule.getId());
        monitors.save(monitor);
        triggerCheck(monitor.getId());
        return monitor;
    }

    public List<Monitor> list(UUID userId) {
        return monitors.findByUserIdOrderByCreatedAtAsc(userId);
    }

    public Monitor get(UUID userId, UUID id) {
        return monitors.findByIdAndUserId(id, userId)
            .orElseThrow(() -> ApiProblems.notFound("Monitor " + id + " not found"));
    }

    public CheckResultRepository.UptimeStats uptime(UUID monitorId) {
        return results.uptime(monitorId);
    }

    /** Partial update; null fields are unchanged. A new URL starts over from UNKNOWN. */
    @Transactional
    public Monitor update(UUID userId, UUID id, MonitorChanges changes) {
        Monitor monitor = get(userId, id);
        Instant now = Instant.now();
        if (changes.name() != null) {
            monitor.setName(validName(changes.name()));
        }
        if (changes.notifyOwner() != null) {
            monitor.setNotifyOwner(changes.notifyOwner());
        }
        boolean recheck = false;
        if (changes.url() != null) {
            String url = safeUrl(changes.url());
            if (!url.equals(monitor.getUrl())) {
                if (monitors.existsByUserIdAndUrlAndIdNot(userId, url, id)) {
                    throw ApiProblems.conflict("You already have a monitor for " + url);
                }
                monitor.setUrl(url);
                // Different site: the old status and any open incident no longer describe it.
                incidents.resolveOpen(id, now);
                monitor.setConsecutiveFailures(0);
                if (monitor.isEnabled()) {
                    monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
                    recheck = true;
                }
            }
        }
        if (changes.intervalSeconds() != null && changes.intervalSeconds() != monitor.getIntervalSeconds()) {
            int interval = validInterval(changes.intervalSeconds());
            monitor.setIntervalSeconds(interval);
            if (monitor.getScheduleId() != null) {
                schedules.update(monitor.getScheduleId(), cronChange(MonitorSchedules.cronFor(id, interval)));
            }
        }
        monitor.setUpdatedAt(now);
        monitors.save(monitor);
        if (recheck) {
            triggerCheck(id);
        }
        return monitor;
    }

    /**
     * Stops checks. An open incident stays open: whether the outage ended is unknown until the next
     * check after resuming, which resolves it if the site is back.
     */
    @Transactional
    public Monitor pause(UUID userId, UUID id) {
        Monitor monitor = get(userId, id);
        if (!monitor.isEnabled()) {
            return monitor;
        }
        monitor.setEnabled(false);
        monitor.setCurrentStatus(MonitorStatus.PAUSED);
        monitor.setConsecutiveFailures(0);
        monitor.setUpdatedAt(Instant.now());
        if (monitor.getScheduleId() != null) {
            schedules.pause(monitor.getScheduleId());
        }
        return monitors.save(monitor);
    }

    @Transactional
    public Monitor resume(UUID userId, UUID id) {
        Monitor monitor = get(userId, id);
        if (monitor.isEnabled()) {
            return monitor;
        }
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setConsecutiveFailures(0);
        monitor.setUpdatedAt(Instant.now());
        if (monitor.getScheduleId() != null) {
            schedules.resume(monitor.getScheduleId());
        }
        monitors.save(monitor);
        triggerCheck(id);
        return monitor;
    }

    /** Deletes the monitor, its history and its schedule. Already-queued checks become no-ops. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Monitor monitor = get(userId, id);
        UUID scheduleId = monitor.getScheduleId();
        monitors.delete(monitor);
        monitors.flush();
        if (scheduleId != null) {
            schedules.delete(scheduleId);
        }
    }

    public Page<CheckResult> checks(UUID userId, UUID id, Instant from, Instant to, Pageable pageable) {
        get(userId, id);
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? Instant.now().plusSeconds(60) : to;
        if (!start.isBefore(end)) {
            throw ApiProblems.badRequest("'from' must be before 'to'");
        }
        return results.findByMonitorIdAndCheckedAtGreaterThanEqualAndCheckedAtLessThan(id, start, end, pageable);
    }

    public Page<Incident> incidents(UUID userId, UUID id, Pageable pageable) {
        get(userId, id);
        return incidents.findByMonitorId(id, pageable);
    }

    // ------------------------------------------------------------------ helpers

    /** Inserts a QUEUED HTTP_CHECK job now and pushes it to Redis once the transaction commits. */
    private void triggerCheck(UUID monitorId) {
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(HttpCheckJobHandler.TYPE);
        job.setPayload(monitorId.toString());
        job.setPriority(3); // a user is waiting for the first result
        job.setStatus(JobStatus.QUEUED);
        job.setMaxAttempts(CHECK_MAX_ATTEMPTS);
        job.setTimeoutSeconds(CHECK_TIMEOUT_SECONDS);
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobs.save(job);
        Runnable enqueue = () -> {
            try {
                queue.enqueue(job.getId(), job.getPriority());
            } catch (RuntimeException e) {
                log.warn("Could not enqueue check {} for monitor {}; the drift sweeper will pick it up",
                    job.getId(), monitorId, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue.run();
                }
            });
        } else {
            enqueue.run();
        }
    }

    private String safeUrl(String url) {
        try {
            return urlValidator.validate(url).url().toString();
        } catch (UnsafeUrlException e) {
            throw ApiProblems.badRequest("This URL can't be monitored: " + e.getMessage());
        }
    }

    private static int validInterval(Integer seconds) {
        if (seconds == null || !MonitorSchedules.ALLOWED_INTERVALS.contains(seconds)) {
            throw ApiProblems.badRequest("intervalSeconds must be one of 60, 300, 900 or 3600");
        }
        return seconds;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiProblems.badRequest("name is required");
        }
        return name.trim();
    }

    private static ScheduleService.ScheduleSpec cronChange(String cron) {
        return new ScheduleService.ScheduleSpec(null, cron, null, null, null, null, null, null, null);
    }
}
