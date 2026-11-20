package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class Reaper {
	private static final String LOCK_KEY = "lock:reaper";
	private static final String LOCK_VALUE = UUID.randomUUID().toString();
	private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private final JobRepository repo;
	private final JobQueue queue;
	private final StringRedisTemplate redis;
	private final MeterRegistry metrics;

	@Scheduled(fixedDelay = 10000)
	public void reap() {
		runWithLock(this::recoverJobsFromLostWorkers);
	}

	@Scheduled(fixedDelay = 10000)
	public void requeueQueuedJobsMissingFromRedis() {
		runWithLock(this::recoverQueuedJobsMissingFromRedis);
	}

	private void runWithLock(Runnable sweep) {
		Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, LOCK_VALUE, Duration.ofSeconds(30));
		if (!Boolean.TRUE.equals(acquired)) {
			return;
		}

		try {
			sweep.run();
		} catch (Exception e) {
			log.error("Unexpected error while reaping jobs", e);
		} finally {
			try {
				redis.execute(RELEASE_LOCK_SCRIPT, List.of(LOCK_KEY), LOCK_VALUE);
			} catch (Exception e) {
				log.warn("Unable to release reaper lock; it will expire automatically", e);
			}
		}
	}

	private void recoverJobsFromLostWorkers() {
		List<Job> runningJobs = repo.findByStatus(JobStatus.RUNNING);
		for (Job job : runningJobs) {
			String workerId = job.getWorkerId();
			boolean workerAlive = workerId != null
				&& Boolean.TRUE.equals(redis.hasKey("worker:" + workerId));
			if (!workerAlive) {
				long secondsSinceUpdate = job.getUpdatedAt() == null ? -1
					: Math.max(0, Duration.between(job.getUpdatedAt(), Instant.now()).toSeconds());
				// Confirm the worker did not renew its heartbeat after the initial check.
				if (workerId != null && Boolean.TRUE.equals(redis.hasKey("worker:" + workerId))) {
					continue;
				}
				job.setStatus(JobStatus.QUEUED);
				job.setLastError("worker lost");
				job.setUpdatedAt(Instant.now());
				repo.save(job);
				queue.enqueue(job.getId(), job.getPriority());
				metrics.counter("jobs_recovered_total", "reason", "worker_lost").increment();
				log.warn("Re-enqueued job {} after worker {} was lost ({} seconds since last update)",
					job.getId(), workerId, secondsSinceUpdate);
			}
		}
	}

	private void recoverQueuedJobsMissingFromRedis() {
		Instant now = Instant.now();
		List<Job> queuedJobs = repo.findByStatusAndUpdatedAtBeforeAndRunAtBefore(
			JobStatus.QUEUED, now.minusSeconds(60), now);

		for (Job job : queuedJobs) {
			String jobId = job.getId().toString();
			if (!isInRedisQueue(jobId)) {
				LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
				if (Thread.interrupted()) {
					Thread.currentThread().interrupt();
					return;
				}
				if (isInRedisQueue(jobId)) {
					continue;
				}

				Job latest = repo.findById(job.getId()).orElse(null);
				if (latest == null || latest.getStatus() != JobStatus.QUEUED) {
					continue;
				}
				queue.enqueue(job.getId(), job.getPriority());
				metrics.counter("jobs_recovered_total", "reason", "missing_from_queue").increment();
				log.warn("Re-enqueued queued job {} missing from Redis", job.getId());
			}
		}
	}

	private boolean isInRedisQueue(String jobId) {
		boolean inReadyQueue = redis.opsForZSet().score(JobQueue.READY, jobId) != null;
		boolean inDelayedQueue = redis.opsForZSet().score(JobQueue.DELAYED, jobId) != null;
		return inReadyQueue || inDelayedQueue;
	}
}
