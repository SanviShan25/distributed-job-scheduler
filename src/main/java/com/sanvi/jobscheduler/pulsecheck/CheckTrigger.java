package com.sanvi.jobscheduler.pulsecheck;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a monitor's check now (outside its schedule): inserts a QUEUED {@code HTTP_CHECK} job in the caller's
 * transaction and pushes it to Redis once that commits. If Redis is unavailable then, the row is already QUEUED in
 * Postgres and the drift sweeper enqueues it within about a minute.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CheckTrigger {
    public static final int CHECK_TIMEOUT_SECONDS = 30;
    public static final int CHECK_MAX_ATTEMPTS = 3;

    private final JobRepository jobs;
    private final JobQueue queue;

    public void checkNow(UUID monitorId) {
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
}
