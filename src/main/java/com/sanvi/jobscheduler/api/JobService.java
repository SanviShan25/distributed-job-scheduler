package com.sanvi.jobscheduler.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.monitor.SchedulerProperties;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobInserter;
import com.sanvi.jobscheduler.repo.JobRepository;
import com.sanvi.jobscheduler.worker.JobHandlerRegistry;
import com.sanvi.jobscheduler.worker.WorkerProperties;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;

@Service
@Profile("api")
@RequiredArgsConstructor
public class JobService {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final JobRepository repo;
    private final JobInserter inserter;
    private final JobQueue queue;
    private final MeterRegistry metrics;
    private final JobHandlerRegistry handlers;
    private final WorkerProperties workerProperties;
    private final SchedulerProperties schedulerProperties;

    /** What a client asked for; null fields take defaults. */
    public record NewJob(String type, String payload, Integer priority, Integer maxAttempts, Integer timeoutSeconds,
        Instant runAt, Long delaySeconds) {}

    public record Submission(Job job, boolean created) {}

    public enum CancelOutcome { CANCELLED, CANCEL_REQUESTED }

    public record Cancellation(Job job, CancelOutcome outcome) {}

    public record JobFilter(List<JobStatus> statuses, String type, String workerId, Instant createdFrom,
        Instant createdTo) {}

    // ------------------------------------------------------------------ submit

    public Submission submit(NewJob request, String idempotencyKey) {
        validateType(request.type());
        validatePayload(request.payload());
        Instant now = Instant.now();
        Instant runAt = resolveRunAt(request, now);

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(request.type());
        job.setPayload(request.payload());
        job.setPriority(request.priority() == null ? 5 : request.priority());
        job.setMaxAttempts(request.maxAttempts() == null ? 3 : request.maxAttempts());
        job.setTimeoutSeconds(request.timeoutSeconds() == null
            ? workerProperties.defaultTimeoutSeconds() : request.timeoutSeconds());
        job.setStatus(runAt.isAfter(now) ? JobStatus.SCHEDULED : JobStatus.QUEUED);
        job.setRunAt(runAt);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        if (idempotencyKey != null) {
            if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw ApiProblems.badRequest("Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
            job.setIdempotencyKey(idempotencyKey);
            // ON CONFLICT DO NOTHING: concurrent requests with the same key create exactly one job.
            if (!inserter.insertIfAbsent(job)) {
                Job existing = repo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> ApiProblems.conflict("Idempotency key is in use; retry the request"));
                return new Submission(existing, false);
            }
        } else {
            repo.save(job);
        }
        // If the API dies between the commit above and this ZADD, the drift sweeper re-enqueues the job.
        queue.enqueueAt(job.getId(), job.getPriority(), job.getRunAt());
        metrics.counter("jobs_submitted_total").increment();
        return new Submission(job, true);
    }

    private Instant resolveRunAt(NewJob request, Instant now) {
        if (request.runAt() != null && request.delaySeconds() != null) {
            throw ApiProblems.badRequest("Specify at most one of runAt and delaySeconds");
        }
        Instant runAt = now;
        if (request.runAt() != null) {
            runAt = request.runAt().isBefore(now) ? now : request.runAt();
        } else if (request.delaySeconds() != null && request.delaySeconds() > 0) {
            runAt = now.plusSeconds(request.delaySeconds());
        }
        Duration maxAhead = schedulerProperties.maxScheduleAhead();
        if (runAt.isAfter(now.plus(maxAhead))) {
            throw ApiProblems.badRequest("runAt must be within " + maxAhead.toDays() + " days");
        }
        return runAt;
    }

    public void validateType(String type) {
        if (!handlers.supports(type)) {
            throw ApiProblems.badRequest("Unknown job type '" + type + "'. Supported types: " + handlers.types());
        }
    }

    public static void validatePayload(String payload) {
        if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw ApiProblems.badRequest("payload must be at most 64 KB (UTF-8 encoded)");
        }
    }

    // ------------------------------------------------------------------ read

    public Job get(UUID id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }

    public Page<Job> list(JobFilter filter, Pageable pageable) {
        return repo.findAll(specificationFor(filter), pageable);
    }

    private static Specification<Job> specificationFor(JobFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                predicates.add(root.get("status").in(f.statuses()));
            }
            if (f.type() != null) {
                predicates.add(cb.equal(root.get("type"), f.type()));
            }
            if (f.workerId() != null) {
                predicates.add(cb.equal(root.get("workerId"), f.workerId()));
            }
            if (f.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.createdFrom()));
            }
            if (f.createdTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), f.createdTo()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // ------------------------------------------------------------------ transitions

    public Job requeue(UUID id) {
        if (repo.requeueDead(id, Instant.now()) == 0) {
            Job existing = get(id);
            throw ApiProblems.conflict("Only DEAD jobs can be requeued (job is " + existing.getStatus() + ")");
        }
        Job job = get(id);
        queue.removeFromDead(id);
        queue.enqueue(job.getId(), job.getPriority());
        return job;
    }

    /**
     * QUEUED/SCHEDULED jobs are cancelled immediately. RUNNING jobs get a cancel request that the
     * owning worker acts on (it interrupts the handler and marks the job CANCELLED).
     */
    public Cancellation cancel(UUID id) {
        // A job can move between the two conditional updates (e.g. claimed in between), so retry a few times.
        for (int i = 0; i < 3; i++) {
            Instant now = Instant.now();
            if (repo.cancelPending(id, now) == 1) {
                queue.remove(id);
                metrics.counter("jobs_cancelled_total").increment();
                return new Cancellation(get(id), CancelOutcome.CANCELLED);
            }
            if (repo.requestCancel(id, now) == 1) {
                queue.requestCancel(id, schedulerProperties.cancelHintTtl());
                return new Cancellation(get(id), CancelOutcome.CANCEL_REQUESTED);
            }
            Job current = get(id);
            if (current.getStatus().isTerminal()) {
                throw ApiProblems.conflict("Job is already " + current.getStatus());
            }
        }
        throw ApiProblems.conflict("Job changed state concurrently; retry the request");
    }

    private static RuntimeException notFound(UUID id) {
        return ApiProblems.notFound("Job " + id + " not found");
    }
}
