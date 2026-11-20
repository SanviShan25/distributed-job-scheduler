package com.sanvi.jobscheduler.support;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.repo.JobRepository;

/**
 * Base class for integration tests. All subclasses share one cached application context
 * (API profile, Testcontainers Postgres + Redis, background scheduling disabled). Each test starts
 * from an empty jobs table and an empty Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "test"})
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JobRepository repo;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void resetState() {
        repo.deleteAllInBatch();
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    /** Builds (does not save) an EMAIL job in the given state. */
    protected static Job job(JobStatus status, int attempts, int maxAttempts) {
        Instant now = Instant.now();
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType("EMAIL");
        job.setPayload("test");
        job.setPriority(5);
        job.setStatus(status);
        job.setAttempts(attempts);
        job.setMaxAttempts(maxAttempts);
        job.setTimeoutSeconds(300);
        job.setRunAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    protected Job running(String workerId, int attempts, int maxAttempts) {
        Job job = job(JobStatus.RUNNING, attempts, maxAttempts);
        job.setWorkerId(workerId);
        job.setLeaseToken(UUID.randomUUID());
        job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        return repo.saveAndFlush(job);
    }

    protected boolean inReadyQueue(UUID id) {
        return redis.opsForZSet().score("queue:ready", id.toString()) != null;
    }

    protected boolean inDelayedQueue(UUID id) {
        return redis.opsForZSet().score("queue:delayed", id.toString()) != null;
    }

    protected Job reload(UUID id) {
        return repo.findById(id).orElseThrow();
    }
}
