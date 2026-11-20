package com.sanvi.jobscheduler.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * Pulls job ids from Redis, claims them in Postgres (conditional update + fencing token) and runs the
 * handler on a separate thread so it can be timed out, cancelled and kept alive with lease renewals.
 *
 * <p>Lifecycle: stops in an early {@link SmartLifecycle} phase, i.e. before {@code HeartbeatService}
 * and before the Redis/JPA infrastructure, so in-flight jobs can finish and report their result.
 */
@Component @Profile("worker")
@Slf4j
public class WorkerLoop implements SmartLifecycle {
    /** Stop before HeartbeatService (lower phase = stopped later). */
    public static final int PHASE = Integer.MAX_VALUE - 100;
    static final int MAX_ERROR_LENGTH = 2000;
    private static final long CANCEL_POLL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final JobQueue queue;
    private final JobRepository repo;
    private final JobHandlerRegistry handlers;
    private final MeterRegistry metrics;
    private final Timer jobDurationTimer;
    private final Timer queueWaitTimer;
    private final WorkerProperties props;
    private final BackoffPolicy backoff;
    private final Clock clock;
    private final String workerId;
    private final Map<UUID, Future<?>> inFlight = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile boolean forceStopping;
    private ExecutorService pollers;
    private ExecutorService handlerExecutor = newHandlerExecutor();

    @Autowired
    public WorkerLoop(JobQueue queue, JobRepository repo, JobHandlerRegistry handlers, MeterRegistry metrics,
            Timer jobDurationTimer, WorkerProperties props) {
        this(queue, repo, handlers, metrics, jobDurationTimer, props,
            new BackoffPolicy(Duration.ofSeconds(props.baseBackoffSeconds()),
                Duration.ofSeconds(props.maxBackoffSeconds())),
            Clock.systemUTC());
    }

    WorkerLoop(JobQueue queue, JobRepository repo, JobHandlerRegistry handlers, MeterRegistry metrics,
            Timer jobDurationTimer, WorkerProperties props, BackoffPolicy backoff, Clock clock) {
        this.queue = queue;
        this.repo = repo;
        this.handlers = handlers;
        this.metrics = metrics;
        this.jobDurationTimer = jobDurationTimer;
        this.props = props;
        this.backoff = backoff;
        this.clock = clock;
        this.workerId = props.id();
        this.queueWaitTimer = Timer.builder("job_queue_wait")
            .description("Time from when a job became runnable (runAt) until a worker claimed it")
            .publishPercentileHistogram()
            .register(metrics);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (props.concurrency() < 1) {
            throw new IllegalStateException("worker.concurrency must be at least 1");
        }
        if (handlerExecutor.isShutdown()) {
            handlerExecutor = newHandlerExecutor();
        }
        forceStopping = false;
        running = true;
        AtomicInteger threadNumber = new AtomicInteger(1);
        pollers = Executors.newFixedThreadPool(props.concurrency(),
            task -> new Thread(task, "worker-" + workerId + "-" + threadNumber.getAndIncrement()));
        for (int i = 0; i < props.concurrency(); i++) {
            pollers.submit(this::runWorkerLoop);
        }
    }

    /**
     * Stops polling immediately, lets in-flight jobs run for {@code worker.shutdown-grace-seconds},
     * then interrupts them. Interrupted jobs are handed back to the queue without consuming an attempt.
     */
    @Override
    public synchronized void stop() {
        if (pollers == null) {
            return;
        }
        running = false;
        pollers.shutdown();
        int inFlightAtStop = inFlight.size();
        if (inFlightAtStop > 0) {
            log.info("Worker {} stopping; waiting up to {}s for {} in-flight job(s)", workerId,
                props.shutdownGraceSeconds(), inFlightAtStop);
        }
        try {
            if (!pollers.awaitTermination(props.shutdownGraceSeconds(), TimeUnit.SECONDS)) {
                forceStop();
                if (!pollers.awaitTermination(5, TimeUnit.SECONDS)) {
                    pollers.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            forceStop();
            pollers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            handlerExecutor.shutdownNow();
            pollers = null;
        }
    }

    private void forceStop() {
        forceStopping = true;
        log.warn("Worker {} shutdown grace period expired; interrupting {} job(s) and returning them to the queue",
            workerId, inFlight.size());
        inFlight.values().forEach(future -> future.cancel(true));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    // ---------------------------------------------------------------- polling

    private void runWorkerLoop() {
        while (running) {
            try {
                Optional<UUID> popped = queue.pop(props.pollTimeout());
                if (popped.isEmpty()) {
                    continue;
                }
                if (!running) {
                    returnUnstartedJob(popped.get());
                    continue;
                }
                process(popped.get());
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("Unexpected error in worker loop for {} on thread {}", workerId,
                    Thread.currentThread().getName(), e);
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Popped from Redis just as shutdown began; it was never claimed, so put it straight back. */
    private void returnUnstartedJob(UUID id) {
        repo.findById(id)
            .filter(job -> job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.SCHEDULED)
            .ifPresent(job -> queue.enqueueAt(job.getId(), job.getPriority(), job.getRunAt()));
    }

    // ---------------------------------------------------------------- processing

    void process(UUID id) {
        MDC.put("jobId", id.toString());
        MDC.put("workerId", workerId);
        try {
            processWithContext(id);
        } finally {
            MDC.remove("jobId");
            MDC.remove("workerId");
            MDC.remove("attempt");
        }
    }

    private void processWithContext(UUID id) {
        UUID token = UUID.randomUUID();
        Instant now = clock.instant();
        if (repo.claim(id, workerId, token, now, now.plus(props.lease())) == 0) {
            log.debug("skipped: job {} is not claimable by worker {}", id, workerId);
            metrics.counter("jobs_duplicate_skipped_total").increment();
            return;
        }

        Job job = repo.findById(id).orElse(null);
        if (job == null) {
            return;
        }
        MDC.put("attempt", String.valueOf(job.getAttempts()));
        recordQueueWait(job, now);
        log.info("Picked up job {} on worker {} (attempt {}/{}) on thread {}", job.getId(), workerId,
            job.getAttempts(), job.getMaxAttempts(), Thread.currentThread().getName());

        Optional<JobHandler> handler = handlers.find(job.getType());
        if (handler.isEmpty()) {
            markDead(job, token, "no handler registered for job type '" + job.getType() + "'");
            return;
        }

        Timer.Sample sample = Timer.start(metrics);
        Outcome outcome;
        try {
            outcome = execute(job, token, handler.get());
        } finally {
            sample.stop(jobDurationTimer);
        }

        switch (outcome.kind()) {
            case SUCCEEDED -> markSucceeded(job, token);
            case FAILED -> handleFailure(job, token, outcome.error());
            case TIMED_OUT -> {
                metrics.counter("jobs_timeout_total").increment();
                handleFailure(job, token, new TimeoutException("timeout"));
            }
            case CANCELLED -> markCancelled(job, token);
            case LOST_LEASE -> log.warn("Job {} lost its lease on worker {}; abandoning result", job.getId(), workerId);
            case SHUTDOWN -> releaseForShutdown(job, token);
        }
    }

    private Outcome execute(Job job, UUID token, JobHandler handler) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        Future<?> future = handlerExecutor.submit(() -> {
            if (logContext != null) {
                MDC.setContextMap(logContext); // handler log lines carry jobId/workerId/attempt too
            }
            try {
                handler.handle(job.getPayload());
                return null;
            } finally {
                MDC.clear();
            }
        });
        inFlight.put(job.getId(), future);
        try {
            if (forceStopping) {
                future.cancel(true);
            }
            long deadline = System.nanoTime() + timeoutFor(job).toNanos();
            long renewEvery = props.leaseRenewInterval().toNanos();
            long nextRenewal = System.nanoTime() + renewEvery;
            while (true) {
                long now = System.nanoTime();
                long remaining = deadline - now;
                if (remaining <= 0) {
                    future.cancel(true);
                    return Outcome.of(Kind.TIMED_OUT);
                }
                long wait = Math.min(remaining, Math.min(CANCEL_POLL_NANOS, Math.max(1, nextRenewal - now)));
                try {
                    future.get(wait, TimeUnit.NANOSECONDS);
                    return Outcome.of(Kind.SUCCEEDED);
                } catch (TimeoutException slice) {
                    if (cancelHinted(job)) {
                        future.cancel(true);
                        return Outcome.of(Kind.CANCELLED);
                    }
                    if (System.nanoTime() >= nextRenewal && deadline - System.nanoTime() > 0) {
                        Kind lost = renewLease(job, token);
                        if (lost != null) {
                            future.cancel(true);
                            return Outcome.of(lost);
                        }
                        nextRenewal = System.nanoTime() + renewEvery;
                    }
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    return new Outcome(Kind.FAILED, cause != null ? cause : e);
                } catch (CancellationException e) {
                    return forceStopping ? Outcome.of(Kind.SHUTDOWN) : new Outcome(Kind.FAILED, e);
                } catch (InterruptedException e) {
                    future.cancel(true);
                    return Outcome.of(Kind.SHUTDOWN);
                }
            }
        } finally {
            inFlight.remove(job.getId());
        }
    }

    /**
     * @return null if the lease was extended (or the DB was briefly unreachable), otherwise why the job
     *         must stop: {@code CANCELLED} if a cancel was requested, {@code LOST_LEASE} if we no longer own it
     */
    private Kind renewLease(Job job, UUID token) {
        try {
            Instant now = clock.instant();
            if (repo.renewLease(job.getId(), workerId, token, now.plus(props.lease()), now) > 0) {
                return null;
            }
            boolean cancelRequested = repo.findById(job.getId())
                .filter(current -> current.getStatus() == JobStatus.RUNNING && current.isCancelRequested()
                    && token.equals(current.getLeaseToken()))
                .isPresent();
            return cancelRequested ? Kind.CANCELLED : Kind.LOST_LEASE;
        } catch (RuntimeException e) {
            log.warn("Could not renew lease for job {} on worker {}", job.getId(), workerId, e);
            return null;
        }
    }

    private boolean cancelHinted(Job job) {
        try {
            return queue.isCancelRequested(job.getId());
        } catch (RuntimeException e) {
            return false; // the DB flag is checked at the next lease renewal
        }
    }

    private void markCancelled(Job job, UUID token) {
        if (repo.markCancelled(job.getId(), workerId, token, clock.instant()) > 0) {
            metrics.counter("jobs_cancelled_total").increment();
            log.info("Job {} cancelled while running on worker {}", job.getId(), workerId);
        }
        try {
            queue.clearCancel(job.getId());
        } catch (RuntimeException e) {
            log.debug("Could not clear cancel hint for job {}", job.getId(), e);
        }
    }

    private void recordQueueWait(Job job, Instant claimedAt) {
        Instant runnableSince = job.getRunAt() != null ? job.getRunAt() : job.getCreatedAt();
        if (runnableSince != null && !claimedAt.isBefore(runnableSince)) {
            queueWaitTimer.record(Duration.between(runnableSince, claimedAt));
        }
    }

    private Duration timeoutFor(Job job) {
        int seconds = job.getTimeoutSeconds() > 0 ? job.getTimeoutSeconds() : props.defaultTimeoutSeconds();
        return Duration.ofSeconds(seconds);
    }

    private void markSucceeded(Job job, UUID token) {
        if (repo.markSucceeded(job.getId(), workerId, token, clock.instant()) > 0) {
            log.info("Job {} succeeded on worker {}", job.getId(), workerId);
            metrics.counter("jobs_completed_total", "status", "success").increment();
        } else {
            log.warn("Skipped success update for job {}: worker {} no longer owns it", job.getId(), workerId);
        }
    }

    private void handleFailure(Job job, UUID token, Throwable failure) {
        String message = describe(failure);
        if (failure instanceof NonRetryableJobException) {
            markDead(job, token, message);
            return;
        }
        if (job.getAttempts() >= job.getMaxAttempts()) {
            markDead(job, token, message);
            return;
        }
        Instant now = clock.instant();
        Instant runAt = now.plus(backoff.delayFor(job.getAttempts()));
        if (repo.markForRetry(job.getId(), workerId, token, message, runAt, now) > 0) {
            queue.enqueueDelayed(job.getId(), job.getPriority(), runAt.toEpochMilli());
            log.info("Job {} failed on worker {} (attempt {}/{}); retrying at {}: {}", job.getId(), workerId,
                job.getAttempts(), job.getMaxAttempts(), runAt, message);
            metrics.counter("jobs_completed_total", "status", "retry").increment();
        } else {
            log.warn("Skipped retry update for job {}: worker {} no longer owns it", job.getId(), workerId);
        }
    }

    private void markDead(Job job, UUID token, String message) {
        if (repo.markDead(job.getId(), workerId, token, truncate(message), clock.instant()) > 0) {
            queue.toDead(job.getId());
            log.warn("Job {} moved to DEAD on worker {} (attempt {}/{}): {}", job.getId(), workerId,
                job.getAttempts(), job.getMaxAttempts(), message);
            metrics.counter("jobs_completed_total", "status", "dead").increment();
        } else {
            log.warn("Skipped DEAD update for job {}: worker {} no longer owns it", job.getId(), workerId);
        }
    }

    private void releaseForShutdown(Job job, UUID token) {
        // Clear the interrupt flag so the JDBC/Redis calls below are not aborted.
        boolean interrupted = Thread.interrupted();
        try {
            if (repo.releaseForShutdown(job.getId(), workerId, token, clock.instant()) > 0) {
                queue.enqueue(job.getId(), job.getPriority());
                log.warn("Job {} interrupted by shutdown of worker {}; returned to queue without using an attempt",
                    job.getId(), workerId);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static String describe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getName();
        }
        return truncate(message);
    }

    static String truncate(String message) {
        return message != null && message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private static ExecutorService newHandlerExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("job-handler-", 0).factory());
    }

    enum Kind { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, LOST_LEASE, SHUTDOWN }

    record Outcome(Kind kind, Throwable error) {
        static Outcome of(Kind kind) {
            return new Outcome(kind, null);
        }
    }
}
