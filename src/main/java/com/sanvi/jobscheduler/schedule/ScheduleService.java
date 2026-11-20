package com.sanvi.jobscheduler.schedule;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sanvi.jobscheduler.api.ApiProblems;
import com.sanvi.jobscheduler.api.JobService;
import com.sanvi.jobscheduler.domain.JobSchedule;
import com.sanvi.jobscheduler.repo.JobScheduleRepository;
import com.sanvi.jobscheduler.worker.WorkerProperties;

import lombok.RequiredArgsConstructor;

@Service
@Profile("api")
@RequiredArgsConstructor
public class ScheduleService {
    private final JobScheduleRepository repo;
    private final JobService jobService;
    private final WorkerProperties workerProperties;

    public record ScheduleSpec(String name, String cron, String timezone, String type, String payload,
        Integer priority, Integer maxAttempts, Integer timeoutSeconds, Boolean enabled) {}

    @Transactional
    public JobSchedule create(ScheduleSpec spec) {
        if (repo.existsByName(spec.name())) {
            throw ApiProblems.conflict("A schedule named '" + spec.name() + "' already exists");
        }
        Instant now = Instant.now();
        JobSchedule s = new JobSchedule();
        s.setId(UUID.randomUUID());
        s.setName(spec.name());
        s.setCron(CronSupport.normalize(spec.cron()));
        s.setTimezone(CronSupport.zone(spec.timezone()).getId());
        s.setType(spec.type());
        s.setPayload(spec.payload());
        s.setPriority(spec.priority() == null ? 5 : spec.priority());
        s.setMaxAttempts(spec.maxAttempts() == null ? 3 : spec.maxAttempts());
        s.setTimeoutSeconds(spec.timeoutSeconds() == null ? workerProperties.defaultTimeoutSeconds()
            : spec.timeoutSeconds());
        s.setEnabled(spec.enabled() == null || spec.enabled());
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        validate(s);
        reschedule(s, now);
        return repo.save(s);
    }

    /** Partial update: null fields are left unchanged. */
    @Transactional
    public JobSchedule update(UUID id, ScheduleSpec spec) {
        JobSchedule s = get(id);
        Instant now = Instant.now();
        boolean timingChanged = false;
        if (spec.name() != null && !spec.name().equals(s.getName())) {
            if (repo.existsByName(spec.name())) {
                throw ApiProblems.conflict("A schedule named '" + spec.name() + "' already exists");
            }
            s.setName(spec.name());
        }
        if (spec.cron() != null) {
            s.setCron(CronSupport.normalize(spec.cron()));
            timingChanged = true;
        }
        if (spec.timezone() != null) {
            s.setTimezone(CronSupport.zone(spec.timezone()).getId());
            timingChanged = true;
        }
        if (spec.type() != null) {
            s.setType(spec.type());
        }
        if (spec.payload() != null) {
            s.setPayload(spec.payload());
        }
        if (spec.priority() != null) {
            s.setPriority(spec.priority());
        }
        if (spec.maxAttempts() != null) {
            s.setMaxAttempts(spec.maxAttempts());
        }
        if (spec.timeoutSeconds() != null) {
            s.setTimeoutSeconds(spec.timeoutSeconds());
        }
        if (spec.enabled() != null && spec.enabled() != s.isEnabled()) {
            s.setEnabled(spec.enabled());
            timingChanged = true;
        }
        validate(s);
        if (timingChanged) {
            reschedule(s, now);
        }
        s.setUpdatedAt(now);
        return s;
    }

    @Transactional
    public JobSchedule pause(UUID id) {
        return update(id, new ScheduleSpec(null, null, null, null, null, null, null, null, false));
    }

    /** Resuming does not back-fill runs missed while paused; the next run is the next cron time from now. */
    @Transactional
    public JobSchedule resume(UUID id) {
        return update(id, new ScheduleSpec(null, null, null, null, null, null, null, null, true));
    }

    @Transactional
    public void delete(UUID id) {
        repo.delete(get(id));
    }

    public JobSchedule get(UUID id) {
        return repo.findById(id).orElseThrow(() -> ApiProblems.notFound("Schedule " + id + " not found"));
    }

    public Page<JobSchedule> list(Pageable pageable) {
        return repo.findAll(pageable);
    }

    private void validate(JobSchedule s) {
        CronSupport.parse(s.getCron());
        jobService.validateType(s.getType());
        JobService.validatePayload(s.getPayload());
    }

    private static void reschedule(JobSchedule s, Instant now) {
        s.setNextRunAt(s.isEnabled() ? CronSupport.next(s.getCron(), s.getTimezone(), now) : null);
    }
}
