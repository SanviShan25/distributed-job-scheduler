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
import org.springframework.jdbc.core.JdbcTemplate;

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
    @Autowired JdbcTemplate jdbc;

    /** A RUNNING job whose lease already ran out (worker stopped renewing). */
    private Job expiredRunning(String workerId, int attempts, int maxAttempts) {
        Job job = running(workerId, attempts, maxAttempts);
        job.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        return repo.saveAndFlush(job);
    }

    private Reaper reaperUsing(JobRepository repository) {
        return new Reaper(repository, queue, redis, metrics, lock, props);
    }

    @Test
    void reaperDoesNotOverwriteAJobThatSucceededAfterItWasRead() {
        Job job = expiredRunning("ghost-worker", 1, 3);
        UUID token = job.getLeaseToken();

        // The reaper reads expired leases, then the (slow but alive) worker commits SUCCEEDED,
        // then the reaper acts on its stale snapshot.
        JobRepository racingRepo = mock(JobRepository.class, AdditionalAnswers.delegatesTo(repo));
        doAnswer(invocation -> {
            List<Job> snapshot = repo.findRunningWithExpiredLease();
            assertThat(repo.markSucceeded(job.getId(), "ghost-worker", token, Instant.now())).isEqualTo(1);
            return snapshot;
        }).when(racingRepo).findRunningWithExpiredLease();

        reaperUsing(racingRepo).recoverExpiredLeases();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(after.getLastError()).isNull();
        assertThat(inReadyQueue(job.getId())).isFalse();
    }

    @Test
    void reaperDoesNotRecoverALeaseRenewedAfterItWasRead() {
        Job job = expiredRunning("slow-worker", 1, 3);
        JobRepository racingRepo = mock(JobRepository.class, AdditionalAnswers.delegatesTo(repo));
        doAnswer(invocation -> {
            List<Job> snapshot = repo.findRunningWithExpiredLease();
            assertThat(repo.renewLease(job.getId(), "slow-worker", job.getLeaseToken(), 30)).isEqualTo(1);
            return snapshot;
        }).when(racingRepo).findRunningWithExpiredLease();

        reaperUsing(racingRepo).recoverExpiredLeases();

        assertThat(reload(job.getId()).getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(inReadyQueue(job.getId())).isFalse();
    }

    @Test
    void crashedWorkerJobIsRequeuedOnceItsLeaseExpires() {
        Job job = expiredRunning("crashed-worker", 1, 3); // no heartbeat either

        reaper.reap();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.getLastError()).isEqualTo("worker lost (lease expired)");
        assertThat(after.getLeaseToken()).isNull();
        assertThat(inReadyQueue(job.getId())).isTrue();
        assertThat(metrics.find("jobs_recovered_total").tag("reason", "worker_lost").counter().count())
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    void missingHeartbeatAloneNeverTriggersRecovery() {
        Job job = running("worker-a", 1, 3);        // valid 30 s lease
        redis.opsForValue().set("worker:worker-a", "alive", Duration.ofSeconds(30));
        redis.opsForValue().set("worker:worker-b", "alive", Duration.ofSeconds(30));

        // Simulate a Redis wipe / heartbeat loss while the job is still running.
        var heartbeatKeys = redis.keys("worker:*");
        assertThat(heartbeatKeys).isNotEmpty();
        redis.delete(heartbeatKeys);

        reaper.reap();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(after.getLastError()).isNull();
        assertThat(inReadyQueue(job.getId())).isFalse();
    }

    @Test
    void workerThatStopsRenewingIsRecoveredAfterTheLeaseExpires() throws Exception {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        redis.opsForValue().set("worker:frozen", "alive", Duration.ofSeconds(30)); // heartbeat still fine
        assertThat(repo.claim(job.getId(), "frozen", UUID.randomUUID(), 1)).isEqualTo(1); // 1 s lease, never renewed

        reaper.reap();
        assertThat(reload(job.getId()).getStatus()).as("lease still valid").isEqualTo(JobStatus.RUNNING);

        Thread.sleep(1_300);
        reaper.reap();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.getLastError()).isEqualTo("lease expired");
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
            // A worker claims the job, then crashes before reporting or renewing (lease of 0 s).
            assertThat(repo.claim(job.getId(), "doomed-" + attempt, UUID.randomUUID(), 0)).isEqualTo(1);
            reaper.recoverExpiredLeases();
        }

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(after.getAttempts()).isEqualTo(3);
        assertThat(after.getLastError()).isEqualTo("worker lost (lease expired); max attempts exhausted");
        assertThat(after.getCompletedAt()).isNotNull();
        assertThat(redis.opsForList().range(JobQueue.DEAD, 0, -1)).containsExactly(job.getId().toString());
        // ...and it can never be claimed again.
        assertThat(repo.claim(job.getId(), "another", UUID.randomUUID(), 30)).isZero();
    }

    @Test
    void claimRefusesQueuedJobWithExhaustedAttempts() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 3, 3));
        assertThat(repo.claim(job.getId(), "w", UUID.randomUUID(), 30)).isZero();
    }

    @Test
    void leaseIsSetFromTheDatabaseClock() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        repo.claim(job.getId(), "w", UUID.randomUUID(), 30);

        Boolean leaseIsNowPlus30 = jdbc.queryForObject(
            "select lease_expires_at between now() + interval '29 seconds' and now() + interval '31 seconds' "
                + "from jobs where id = ?", Boolean.class, job.getId());
        assertThat(leaseIsNowPlus30).isTrue();
    }

    @Test
    void staleWorkerCannotCompleteAJobReclaimedUnderTheSameWorkerId() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        UUID staleToken = UUID.randomUUID();
        repo.claim(job.getId(), "worker-1", staleToken, 0);
        redis.opsForValue().set("worker:worker-1", "alive", Duration.ofSeconds(30));

        reaper.recoverExpiredLeases(); // lease expired -> back to QUEUED
        UUID freshToken = UUID.randomUUID();
        // A restarted process reusing the id "worker-1" claims it again.
        assertThat(repo.claim(job.getId(), "worker-1", freshToken, 30)).isEqualTo(1);

        assertThat(repo.markSucceeded(job.getId(), "worker-1", staleToken, Instant.now())).isZero();
        assertThat(repo.markDead(job.getId(), "worker-1", staleToken, "x", Instant.now())).isZero();
        assertThat(repo.markForRetry(job.getId(), "worker-1", staleToken, "x", Instant.now(), Instant.now())).isZero();
        assertThat(repo.renewLease(job.getId(), "worker-1", staleToken, 30)).isZero();
        assertThat(repo.markSucceeded(job.getId(), "worker-1", freshToken, Instant.now())).isEqualTo(1);
    }

    @Test
    void releaseForShutdownDoesNotChargeTheAttempt() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        UUID token = UUID.randomUUID();
        repo.claim(job.getId(), "w", token, 30);

        assertThat(repo.releaseForShutdown(job.getId(), "w", token, Instant.now())).isEqualTo(1);

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void sweeperReAddsQueuedJobsMissingFromRedis() {
        Instant stale = Instant.now().minus(Duration.ofMinutes(5));
        Job lost = staleQueued(stale, stale);
        Job lostRetry = job(JobStatus.SCHEDULED, 1, 3); // retry waiting out its backoff
        lostRetry.setUpdatedAt(stale);
        lostRetry.setRunAt(Instant.now().plus(Duration.ofMinutes(10)));
        repo.saveAndFlush(lostRetry);
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
    void sweeperRecoversScheduledJobsMissingFromRedis() {
        Instant stale = Instant.now().minus(Duration.ofMinutes(5));
        Job due = job(JobStatus.SCHEDULED, 0, 3);
        due.setUpdatedAt(stale);
        due.setRunAt(Instant.now().minusSeconds(30));
        repo.saveAndFlush(due);
        Job future = job(JobStatus.SCHEDULED, 0, 3);
        future.setUpdatedAt(stale);
        future.setRunAt(Instant.now().plus(Duration.ofHours(1)));
        repo.saveAndFlush(future);

        reaper.requeueQueuedJobsMissingFromRedis();

        assertThat(inReadyQueue(due.getId())).isTrue();
        assertThat(inDelayedQueue(future.getId())).isTrue();
    }

    @Test
    void lostWorkerWithPendingCancelEndsCancelledNotRequeued() {
        Job job = expiredRunning("crashed-worker", 1, 3);
        job.setCancelRequested(true);
        repo.saveAndFlush(job);

        reaper.recoverExpiredLeases();

        Job after = reload(job.getId());
        assertThat(after.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(after.getCompletedAt()).isNotNull();
        assertThat(inReadyQueue(job.getId())).isFalse();
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
