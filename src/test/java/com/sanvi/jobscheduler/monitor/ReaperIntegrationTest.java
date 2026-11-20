package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import org.springframework.beans.factory.annotation.Autowired;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.lock.DistributedLock;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import io.micrometer.core.instrument.MeterRegistry;

class ReaperIntegrationTest extends AbstractIntegrationTest {

    @Autowired Reaper reaper;
    @Autowired JobQueue queue;
    @Autowired MeterRegistry metrics;
    @Autowired DistributedLock lock;
    @Autowired SchedulerProperties props;

    @Test
    void reaperDoesNotOverwriteAJobThatSucceededAfterItWasRead() {
        Job job = running("ghost-worker", 1, 3); // no heartbeat -> looks lost
        UUID token = job.getLeaseToken();

        // The reaper reads RUNNING jobs, then the (slow but alive) worker commits SUCCEEDED,
        // then the reaper acts on its stale snapshot.
        JobRepository racingRepo = mock(JobRepository.class, AdditionalAnswers.delegatesTo(repo));
        doAnswer(invocation -> {
            List<Job> snapshot = repo.findByStatus(JobStatus.RUNNING);
            assertThat(repo.markSucceeded(job.getId(), "ghost-worker", token, Instant.now())).isEqualTo(1);
            return snapshot;
        }).when(racingRepo).findByStatus(JobStatus.RUNNING);

        new Reaper(racingRepo, queue, redis, metrics, lock, props).recoverJobsFromLostWorkers();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(after.getLastError()).isNull();
        assertThat(inReadyQueue(job.getId())).isFalse();
    }

    @Test
    void jobFromLostWorkerIsRequeued() {
        Job job = running("crashed-worker", 1, 3);

        reaper.recoverJobsFromLostWorkers();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.getLastError()).isEqualTo("worker lost");
        assertThat(after.getLeaseToken()).isNull();
        assertThat(inReadyQueue(job.getId())).isTrue();
    }

    @Test
    void jobOnLiveWorkerWithValidLeaseIsLeftAlone() {
        redis.opsForValue().set("worker:live-worker", "alive", Duration.ofSeconds(30));
        Job job = running("live-worker", 1, 3);

        reaper.reap();

        assertThat(reload(job.getId()).getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(inReadyQueue(job.getId())).isFalse();
    }

    @Test
    void poisonPillThatKeepsKillingWorkersEndsUpDead() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));

        for (int attempt = 1; attempt <= 3; attempt++) {
            // A worker claims the job, then crashes (no heartbeat) before reporting anything.
            assertThat(repo.claim(job.getId(), "doomed-" + attempt, UUID.randomUUID(), Instant.now(),
                Instant.now().plusSeconds(30))).isEqualTo(1);
            reaper.recoverJobsFromLostWorkers();
        }

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(after.getAttempts()).isEqualTo(3);
        assertThat(after.getLastError()).isEqualTo("worker lost; max attempts exhausted");
        assertThat(redis.opsForList().range(JobQueue.DEAD, 0, -1)).containsExactly(job.getId().toString());
        // ...and it can never be claimed again.
        assertThat(repo.claim(job.getId(), "another", UUID.randomUUID(), Instant.now(), Instant.now()))
            .isZero();
    }

    @Test
    void claimRefusesQueuedJobWithExhaustedAttempts() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 3, 3));
        assertThat(repo.claim(job.getId(), "w", UUID.randomUUID(), Instant.now(), Instant.now())).isZero();
    }

    @Test
    void expiredLeaseIsRecoveredEvenWhileWorkerHeartbeatIsAlive() {
        redis.opsForValue().set("worker:stuck-worker", "alive", Duration.ofSeconds(30));
        Job job = running("stuck-worker", 1, 3);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        repo.saveAndFlush(job);

        reaper.reap();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.getLastError()).isEqualTo("lease expired");
        assertThat(inReadyQueue(job.getId())).isTrue();
    }

    @Test
    void staleWorkerCannotCompleteAJobReclaimedUnderTheSameWorkerId() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        UUID staleToken = UUID.randomUUID();
        repo.claim(job.getId(), "worker-1", staleToken, Instant.now(), Instant.now().minusSeconds(1));
        redis.opsForValue().set("worker:worker-1", "alive", Duration.ofSeconds(30));

        reaper.recoverExpiredLeases(); // lease expired -> back to QUEUED
        UUID freshToken = UUID.randomUUID();
        // A restarted process reusing the id "worker-1" claims it again.
        assertThat(repo.claim(job.getId(), "worker-1", freshToken, Instant.now(), Instant.now().plusSeconds(30)))
            .isEqualTo(1);

        assertThat(repo.markSucceeded(job.getId(), "worker-1", staleToken, Instant.now())).isZero();
        assertThat(repo.markDead(job.getId(), "worker-1", staleToken, "x", Instant.now())).isZero();
        assertThat(repo.markForRetry(job.getId(), "worker-1", staleToken, "x", Instant.now(), Instant.now())).isZero();
        assertThat(repo.markSucceeded(job.getId(), "worker-1", freshToken, Instant.now())).isEqualTo(1);
    }

    @Test
    void releaseForShutdownDoesNotChargeTheAttempt() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        UUID token = UUID.randomUUID();
        repo.claim(job.getId(), "w", token, Instant.now(), Instant.now().plusSeconds(30));

        assertThat(repo.releaseForShutdown(job.getId(), "w", token, Instant.now())).isEqualTo(1);

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void sweeperReAddsQueuedJobsMissingFromRedis() {
        Instant stale = Instant.now().minus(Duration.ofMinutes(5));
        Job lost = staleQueued(stale, stale);
        Job lostRetry = staleQueued(stale, Instant.now().plus(Duration.ofMinutes(10)));
        Job present = staleQueued(stale, stale);
        queue.enqueue(present.getId(), present.getPriority());
        Double presentScore = redis.opsForZSet().score(JobQueue.READY, present.getId().toString());
        Job fresh = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3)); // just submitted; not swept yet

        reaper.requeueQueuedJobsMissingFromRedis();

        assertThat(inReadyQueue(lost.getId())).isTrue();
        assertThat(inDelayedQueue(lostRetry.getId())).as("future retry goes back to the delayed queue").isTrue();
        assertThat(redis.opsForZSet().score(JobQueue.READY, present.getId().toString())).isEqualTo(presentScore);
        assertThat(inReadyQueue(fresh.getId())).isFalse();
        assertThat(redis.opsForZSet().zCard(JobQueue.READY)).isEqualTo(2);
    }

    @Test
    void sweeperHandlesMoreJobsThanOnePage() {
        Instant stale = Instant.now().minus(Duration.ofMinutes(5));
        int count = props.sweeper().pageSize() * 2 + 7;
        for (int i = 0; i < count; i++) {
            Job job = job(JobStatus.QUEUED, 0, 3);
            job.setUpdatedAt(stale);
            repo.save(job);
        }
        repo.flush();

        reaper.requeueQueuedJobsMissingFromRedis();

        assertThat(redis.opsForZSet().zCard(JobQueue.READY)).isEqualTo(count);
    }

    @Test
    void sweeperMovesExhaustedQueuedJobsToDead() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 3, 3));

        reaper.requeueQueuedJobsMissingFromRedis();

        assertThat(reload(job.getId()).getStatus()).isEqualTo(JobStatus.DEAD);
    }

    @Test
    void sweepsUseSeparateLocks() {
        redis.opsForValue().set(Reaper.RUNNING_LOCK_KEY, "someone-else", Duration.ofSeconds(30));
        Job lost = staleQueued(Instant.now().minus(Duration.ofMinutes(5)), Instant.now().minusSeconds(60));

        reaper.requeueQueuedJobsMissingFromRedis();

        assertThat(inReadyQueue(lost.getId())).isTrue();
    }

    private Job staleQueued(Instant updatedAt, Instant runAt) {
        Job job = job(JobStatus.QUEUED, 1, 3);
        job.setUpdatedAt(updatedAt);
        job.setRunAt(runAt);
        return repo.saveAndFlush(job);
    }
}
