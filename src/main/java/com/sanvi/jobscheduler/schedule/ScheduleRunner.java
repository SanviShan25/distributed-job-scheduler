package com.sanvi.jobscheduler.schedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobSchedule;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.monitor.SchedulerProperties;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobInserter;
import com.sanvi.jobscheduler.repo.JobScheduleRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns due schedules into job runs.
 *
 * <p><b>Exclusivity:</b> due rows are locked with {@code SELECT ... FOR UPDATE SKIP LOCKED}, so any number
 * of API instances can run this concurrently and each schedule fire is handled by one of them.
 *
 * <p><b>Exactly one run per fire time:</b> each run gets the idempotency key
 * {@code schedule:{id}:{fireTime}} and is inserted with {@code ON CONFLICT DO NOTHING}, so even a replayed
 * or concurrent fire cannot create a duplicate.
 *
 * <p><b>Misfire policy (run once, skip missed):</b> if the scheduler was down across several fire times,
 * it creates a single run for the earliest missed fire time and then advances {@code next_run_at} to the
 * next cron time after <em>now</em>. Missed intermediate runs are not back-filled.
 */
@Component
@Profile("api")
@Slf4j
public class ScheduleRunner {
    private final JobScheduleRepository schedules;
    private final JobInserter inserter;
    private final JobQueue queue;
    private final MeterRegistry metrics;
    private final SchedulerProperties props;
    private final TransactionTemplate tx;

    public ScheduleRunner(JobScheduleRepository schedules, JobInserter inserter, JobQueue queue,
            MeterRegistry metrics, SchedulerProperties props, PlatformTransactionManager txManager) {
        this.schedules = schedules;
        this.inserter = inserter;
        this.queue = queue;
        this.metrics = metrics;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${scheduler.schedules.poll-interval:1s}")
    public void tick() {
        try {
            fireDue(Instant.now());
        } catch (RuntimeException e) {
            log.error("Schedule runner failed; will retry", e);
        }
    }

    /** Fires every schedule due at {@code now}; returns the number of job runs created. */
    public int fireDue(Instant now) {
        int total = 0;
        while (true) {
            Batch batch = tx.execute(status -> fireBatch(now));
            // Enqueue after commit. If this process dies first, the drift sweeper enqueues the QUEUED rows.
            for (Job job : batch.created()) {
                queue.enqueue(job.getId(), job.getPriority());
                metrics.counter("schedule_runs_created_total").increment();
            }
            total += batch.created().size();
            if (batch.dueCount() < props.schedules().batchSize()) {
                return total;
            }
        }
    }

    private record Batch(List<Job> created, int dueCount) {}

    private Batch fireBatch(Instant now) {
        List<Job> created = new ArrayList<>();
        List<JobSchedule> due = schedules.lockDue(now, props.schedules().batchSize());
        for (JobSchedule schedule : due) {
            Instant fireTime = schedule.getNextRunAt();
            Job job = runFor(schedule, fireTime, now);
            if (inserter.insertIfAbsent(job)) {
                created.add(job);
                log.info("Schedule '{}' fired for {} -> job {}", schedule.getName(), fireTime, job.getId());
            } else {
                log.info("Schedule '{}' run for {} already exists; skipping", schedule.getName(), fireTime);
            }
            schedule.setLastRunAt(fireTime);
            schedule.setNextRunAt(CronSupport.next(schedule.getCron(), schedule.getTimezone(), now));
            schedule.setUpdatedAt(now);
        }
        return new Batch(created, due.size());
    }

    static String idempotencyKey(UUID scheduleId, Instant fireTime) {
        return "schedule:" + scheduleId + ":" + fireTime;
    }

    private static Job runFor(JobSchedule schedule, Instant fireTime, Instant now) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(schedule.getType());
        job.setPayload(schedule.getPayload());
        job.setPriority(schedule.getPriority());
        job.setMaxAttempts(schedule.getMaxAttempts());
        job.setTimeoutSeconds(schedule.getTimeoutSeconds());
        job.setStatus(JobStatus.QUEUED);
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setIdempotencyKey(idempotencyKey(schedule.getId(), fireTime));
        job.setCronScheduleId(schedule.getId());
        return job;
    }
}
