package com.sanvi.jobscheduler.worker;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component @Profile("worker") @RequiredArgsConstructor
@Slf4j
public class WorkerLoop {
    private final JobQueue queue;
    private final JobRepository repo;
    private final List<JobHandler> handlerList;
    private final MeterRegistry metrics;
    private final Timer jobDurationTimer;
    private final Set<UUID> inFlightJobs = ConcurrentHashMap.newKeySet();
    @Value("${worker.id}") String workerId;
    @Value("${worker.concurrency:4}") int concurrency;
    private ExecutorService workerExecutor;

    @PostConstruct
    public void start() {
        if (concurrency < 1) {
            throw new IllegalStateException("worker.concurrency must be at least 1");
        }
        AtomicInteger threadNumber = new AtomicInteger(1);
        workerExecutor = Executors.newFixedThreadPool(concurrency, task -> {
            Thread thread = new Thread(task);
            thread.setName("worker-" + workerId + "-" + threadNumber.getAndIncrement());
            return thread;
        });
        for (int i = 0; i < concurrency; i++) {
            workerExecutor.submit(this::runWorkerLoop);
        }
    }

    private void runWorkerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                queue.moveDueJobs(repo);
                UUID jobId = queue.pop().orElse(null);
                if (jobId == null) {
                    TimeUnit.MILLISECONDS.sleep(50);
                    continue;
                }

                if (!inFlightJobs.add(jobId)) {
                    log.warn("Skipped duplicate in-flight job {} on worker {}", jobId, workerId);
                    continue;
                }
                try {
                    process(jobId);
                } finally {
                    inFlightJobs.remove(jobId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Unexpected error in worker loop for {} on thread {}", workerId,
                    Thread.currentThread().getName(), e);
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (workerExecutor == null) {
            return;
        }
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(65, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void process(UUID id) {
        int claimed = repo.claim(id, workerId, Instant.now());
        if (claimed == 0) {
            log.debug("skipped: already claimed job {} on worker {}", id, workerId);
            metrics.counter("jobs_duplicate_skipped_total").increment();
            return;
        }

        Job job = repo.findById(id).orElse(null);
        if (job == null) return;
        log.info("Picked up job {} on worker {} (attempt {}) on thread {}", job.getId(), workerId,
            job.getAttempts(), Thread.currentThread().getName());

        Timer.Sample sample = Timer.start(metrics);
        Exception failure = null;
        try {
            handlerList.stream().filter(h -> h.type().equals(job.getType()))
                .findFirst().orElseThrow().handle(job.getPayload());
        } catch (Exception e) {
            failure = e;
        } finally {
            sample.stop(jobDurationTimer);
        }

        if (failure == null) {
            if (repo.markSucceeded(job.getId(), workerId, Instant.now()) > 0) {
                log.info("Job {} succeeded on worker {} on thread {}", job.getId(), workerId,
                    Thread.currentThread().getName());
                metrics.counter("jobs_completed_total", "status", "success", "worker", workerId).increment();
            } else {
                log.debug("Skipped success update for job {}: worker {} no longer owns it", job.getId(), workerId);
            }
        } else {
            if (job.getAttempts() >= job.getMaxAttempts()) {
                if (repo.markDead(job.getId(), workerId, failure.getMessage(), Instant.now()) > 0) {
                    log.warn("Job {} moved to DEAD on worker {} (attempt {}) on thread {}: {}", job.getId(), workerId,
                        job.getAttempts(), Thread.currentThread().getName(), failure.getMessage());
                    queue.toDead(job.getId());
                    metrics.counter("jobs_completed_total", "status", "dead", "worker", workerId).increment();
                } else {
                    log.debug("Skipped DEAD update for job {}: worker {} no longer owns it", job.getId(), workerId);
                }
            } else {
                if (repo.markForRetry(job.getId(), workerId, failure.getMessage(), Instant.now()) > 0) {
                    log.info("Job {} failed on worker {} (attempt {}) on thread {}; retrying: {}", job.getId(), workerId,
                        job.getAttempts(), Thread.currentThread().getName(), failure.getMessage());
                    long delay = (long) Math.pow(2, job.getAttempts()) * 1000;
                    queue.enqueueDelayed(job.getId(), System.currentTimeMillis() + delay);
                    metrics.counter("jobs_completed_total", "status", "retry", "worker", workerId).increment();
                } else {
                    log.debug("Skipped retry update for job {}: worker {} no longer owns it", job.getId(), workerId);
                }
            }
        }
    }
}