package com.sanvi.jobscheduler.pulsecheck.email;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobInserter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues emails as {@code SEND_EMAIL} jobs; workers send them with the scheduler's retries and backoff.
 *
 * <ul>
 *   <li>The job row is written in the caller's transaction (the jobs table is the outbox): if the
 *       transaction rolls back, no email; if it commits, the email will be sent even if the process dies
 *       right after.</li>
 *   <li>The Redis enqueue happens after commit. If Redis is unavailable then, the row stays QUEUED and the
 *       scheduler's drift sweeper enqueues it within about a minute.</li>
 *   <li>The idempotency key is the job's unique {@code idempotency_key}: queuing the same email twice
 *       creates one job ({@code ON CONFLICT DO NOTHING}), and the worker passes the key on to Resend.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailOutbox {
    public static final String JOB_TYPE = "SEND_EMAIL";
    static final int MAX_ATTEMPTS = 8;
    static final int TIMEOUT_SECONDS = 60;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JobInserter inserter;
    private final JobQueue queue;

    /** @return true if a new email job was created, false if one with this idempotency key already exists */
    public boolean enqueue(EmailJob email) {
        if (email.idempotencyKey() == null || email.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("every email needs an idempotency key");
        }
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JOB_TYPE);
        job.setPayload(JSON.writeValueAsString(email));
        job.setPriority(2); // ahead of routine checks: someone may be waiting for this email
        job.setStatus(JobStatus.QUEUED);
        job.setMaxAttempts(MAX_ATTEMPTS);
        job.setTimeoutSeconds(TIMEOUT_SECONDS);
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setIdempotencyKey(email.idempotencyKey());
        if (!inserter.insertIfAbsent(job)) {
            log.info("Email {} already queued; not queuing it again", email.idempotencyKey());
            return false;
        }
        Runnable push = () -> {
            try {
                queue.enqueue(job.getId(), job.getPriority());
            } catch (RuntimeException e) {
                log.warn("Could not enqueue email job {}; the drift sweeper will pick it up", job.getId(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
        return true;
    }

    static EmailJob parse(String payload) {
        return JSON.readValue(payload, EmailJob.class);
    }
}
