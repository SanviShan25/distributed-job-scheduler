package com.sanvi.jobscheduler.pulsecheck;

import java.time.Duration;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.lock.DistributedLock;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Daily deletion of check results older than {@code pulsecheck.retention-days} (90), in batches so no
 * statement holds locks for long. One API instance runs it at a time (Redis lock).
 */
@Component
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class CheckResultRetention {
    static final String LOCK_KEY = "lock:pulsecheck-retention";
    private static final int MAX_BATCHES_PER_RUN = 10_000;

    private final CheckResultRepository results;
    private final DistributedLock lock;
    private final PulseCheckProperties props;

    @Scheduled(cron = "${pulsecheck.retention-cron:0 30 3 * * *}", zone = "UTC")
    public void cleanUp() {
        try {
            lock.runExclusively(LOCK_KEY, Duration.ofMinutes(10), handle -> {
                long deleted = deleteExpired(Instant.now(), handle);
                if (deleted > 0) {
                    log.info("PulseCheck retention: deleted {} check result(s) older than {} days", deleted,
                        props.retentionDays());
                }
            });
        } catch (RuntimeException e) {
            log.error("PulseCheck retention failed; will retry tomorrow", e);
        }
    }

    long deleteExpired(Instant now, DistributedLock.Handle handle) {
        Instant cutoff = now.minus(Duration.ofDays(props.retentionDays()));
        long total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = results.deleteOlderThan(cutoff, props.retentionBatchSize());
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
