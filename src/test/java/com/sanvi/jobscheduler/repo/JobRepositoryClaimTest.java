package com.sanvi.jobscheduler.repo;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

class JobRepositoryClaimTest extends AbstractIntegrationTest {

    @Test
    void onlyOneConcurrentClaimCanTransitionQueuedJobToRunning() throws Exception {
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(jobId);
        job.setType("EMAIL");
        job.setPayload("claim-test");
        job.setPriority(1);
        job.setStatus(JobStatus.QUEUED);
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        repo.saveAndFlush(job);

        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = callers.submit(() -> tryClaim("worker-one", jobId, ready, start));
            Future<Integer> second = callers.submit(() -> tryClaim("worker-two", jobId, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "claim callers did not become ready");
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));

            Job claimed = repo.findById(jobId).orElseThrow();
            assertEquals(JobStatus.RUNNING, claimed.getStatus());
            assertEquals(1, claimed.getAttempts());
            assertNotNull(claimed.getWorkerId());
        } finally {
            start.countDown();
            callers.shutdownNow();
            repo.deleteById(jobId);
        }
    }

    private int tryClaim(String workerId, UUID jobId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting to start concurrent claim");
        }
        return repo.claim(jobId, workerId, UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(30));
    }
}
