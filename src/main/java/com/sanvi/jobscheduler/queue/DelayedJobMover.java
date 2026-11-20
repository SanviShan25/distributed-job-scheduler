package com.sanvi.jobscheduler.queue;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.lock.DistributedLock;
import com.sanvi.jobscheduler.monitor.SchedulerProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Promotes due jobs from {@code queue:delayed} to {@code queue:ready}. Runs on every API and worker
 * instance (so delayed jobs keep flowing if either tier is down) but a Redis lock lets only one
 * instance do the work per tick. The move itself is a single atomic Lua script.
 */
@Component
@Profile({"api", "worker"})
@RequiredArgsConstructor
@Slf4j
public class DelayedJobMover {
    static final String LOCK_KEY = "lock:delayed-mover";
    private static final int MAX_BATCHES_PER_TICK = 20;

    private final JobQueue queue;
    private final DistributedLock lock;
    private final SchedulerProperties props;

    @Scheduled(fixedDelay = 500)
    public void moveDueJobs() {
        try {
            lock.runExclusively(LOCK_KEY, props.mover().lockTtl(), handle -> moveNow());
        } catch (RuntimeException e) {
            log.warn("Delayed job mover failed; will retry", e);
        }
    }

    /** Moves everything due right now (bounded per tick); returns the number moved. */
    public long moveNow() {
        int batchSize = props.mover().batchSize();
        long total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
            long moved = queue.moveDueJobs(System.currentTimeMillis(), batchSize);
            total += moved;
            if (moved < batchSize) {
                break;
            }
        }
        return total;
    }
}
