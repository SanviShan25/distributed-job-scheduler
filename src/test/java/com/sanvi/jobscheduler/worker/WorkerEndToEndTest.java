package com.sanvi.jobscheduler.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.DelayedJobMover;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;
import com.sanvi.jobscheduler.support.TestcontainersConfiguration;

/** Real worker profile: lifecycle, blocking pop, claim, handler, and completion against real Redis/Postgres. */
@SpringBootTest(properties = {"worker.id=e2e-worker", "worker.concurrency=2", "worker.base-backoff-seconds=1",
    "worker.max-backoff-seconds=1"})
@ActiveProfiles({"worker", "test"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class WorkerEndToEndTest {

    @Autowired JobRepository repo;
    @Autowired JobQueue queue;
    @Autowired StringRedisTemplate redis;
    @Autowired WorkerLoop workerLoop;
    @Autowired DelayedJobMover mover;

    @Test
    void workerProcessesJobsToCompletion() throws Exception {
        assertThat(workerLoop.isRunning()).isTrue();
        assertThat(redis.hasKey("worker:e2e-worker")).isTrue();

        Job ok = submit("delayMs=10");
        Job failing = submit("delayMs=10 fail");
        failing.setMaxAttempts(2);
        repo.saveAndFlush(failing);
        queue.enqueue(ok.getId(), 5);
        queue.enqueue(failing.getId(), 5);

        awaitStatus(ok.getId(), JobStatus.SUCCEEDED, Duration.ofSeconds(10));
        // First failure is retried through the delayed queue; scheduling is off in tests, so drive the mover.
        Instant deadline = Instant.now().plusSeconds(15);
        while (repo.findById(failing.getId()).orElseThrow().getStatus() != JobStatus.DEAD
                && Instant.now().isBefore(deadline)) {
            mover.moveNow();
            Thread.sleep(100);
        }
        Job dead = repo.findById(failing.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(JobStatus.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(2);
        assertThat(dead.getLastError()).isEqualTo("simulated failure");
    }

    private Job submit(String payload) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType("EMAIL");
        job.setPayload(payload);
        job.setPriority(5);
        job.setStatus(JobStatus.QUEUED);
        job.setMaxAttempts(3);
        job.setTimeoutSeconds(30);
        Instant now = Instant.now();
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return repo.saveAndFlush(job);
    }

    private void awaitStatus(UUID id, JobStatus expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (repo.findById(id).orElseThrow().getStatus() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo(expected);
    }
}
