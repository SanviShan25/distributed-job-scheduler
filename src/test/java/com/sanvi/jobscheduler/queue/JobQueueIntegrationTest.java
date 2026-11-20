package com.sanvi.jobscheduler.queue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

class JobQueueIntegrationTest extends AbstractIntegrationTest {

    @Autowired JobQueue queue;
    @Autowired DelayedJobMover mover;

    @Test
    void moverPromotesOnlyDueJobsUsingTheStoredPriority() {
        long now = System.currentTimeMillis();
        UUID due = UUID.randomUUID();
        UUID notYet = UUID.randomUUID();
        queue.enqueueDelayed(due, 2, now - 1_000);
        queue.enqueueDelayed(notYet, 1, now + 60_000);

        assertThat(mover.moveNow()).isEqualTo(1);

        assertThat(redis.opsForZSet().score(JobQueue.READY, due.toString()))
            .isEqualTo(JobQueue.readyScore(2, now - 1_000));
        assertThat(inDelayedQueue(due)).isFalse();
        assertThat(redis.opsForHash().hasKey(JobQueue.DELAYED_PRIORITY, due.toString())).isFalse();
        assertThat(inDelayedQueue(notYet)).isTrue();
        assertThat(inReadyQueue(notYet)).isFalse();
    }

    @Test
    void promotedJobsKeepPriorityOrder() {
        long past = System.currentTimeMillis() - 5_000;
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        queue.enqueueDelayed(low, 9, past);
        queue.enqueueDelayed(high, 1, past + 1_000);
        mover.moveNow();

        assertThat(queue.pop(Duration.ofMillis(100))).contains(high);
        assertThat(queue.pop(Duration.ofMillis(100))).contains(low);
    }

    @Test
    void moverDrainsMoreThanOneBatch() {
        long past = System.currentTimeMillis() - 1_000;
        int count = 2_500;
        for (int i = 0; i < count; i++) {
            queue.enqueueDelayed(UUID.randomUUID(), 5, past);
        }
        assertThat(mover.moveNow()).isEqualTo(count);
        assertThat(redis.opsForZSet().zCard(JobQueue.DELAYED)).isZero();
    }

    @Test
    void blockingPopReturnsEmptyAfterTimeout() {
        long start = System.nanoTime();
        Optional<UUID> popped = queue.pop(Duration.ofMillis(300));
        assertThat(popped).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(250));
    }

    @Test
    void findMissingChecksBothQueues() {
        UUID ready = UUID.randomUUID();
        UUID delayed = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        queue.enqueue(ready, 5);
        queue.enqueueDelayed(delayed, 5, System.currentTimeMillis() + 60_000);

        assertThat(queue.findMissing(List.of(ready, delayed, missing))).containsExactly(missing);
    }
}
