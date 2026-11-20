package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.lock.DistributedLock;
import com.sanvi.jobscheduler.repo.JobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes SUCCEEDED and CANCELLED jobs whose {@code completed_at} is older than
 * {@code scheduler.retention-days}, in small batches so no single statement holds locks for long.
 * DEAD jobs are kept for inspection and manual requeue.
 */
@Component
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class RetentionCleaner {
    static final String LOCK_KEY = "lock:retention";
    private static final int MAX_BATCHES_PER_RUN = 1000;

    private final JobRepository repo;
    private final DistributedLock lock;
    private final SchedulerProperties props;

    @Scheduled(fixedDelayString = "${scheduler.retention-interval:1h}", initialDelayString = "1m")
    public void cleanUp() {
        try {
            lock.runExclusively(LOCK_KEY, Duration.ofMinutes(5), handle -> {
                long deleted = deleteExpired(Instant.now(), handle);
                if (deleted > 0) {
                    log.info("Retention: deleted {} finished job(s) older than {} days", deleted, props.retentionDays());
                }
            });
        } catch (RuntimeException e) {
            log.error("Retention cleanup failed; will retry", e);
        }
    }

    long deleteExpired(Instant now, DistributedLock.Handle handle) {
        Instant cutoff = now.minus(Duration.ofDays(props.retentionDays()));
        long total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = repo.deleteFinishedBefore(cutoff, props.retentionBatchSize());
            total += deleted;
            if (deleted < props.retentionBatchSize()) {
                break;
            }
            if (handle != null) {
                handle.renew();
            }
        }
        return total;
    }
}
