package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.lock.DistributedLock;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Recovery sweeps. Each sweep has its own Redis lock so only one API instance runs it at a time,
 * and every state change is a conditional update so a sweep racing a worker can never clobber it.
 */
@Component
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class Reaper {
	static final String RUNNING_LOCK_KEY = "lock:reaper:running";
	static final String DRIFT_LOCK_KEY = "lock:reaper:queue-drift";
	private static final UUID MIN_UUID = new UUID(0, 0);

	private final JobRepository repo;
	private final JobQueue queue;
	private final StringRedisTemplate redis;
	private final MeterRegistry metrics;
	private final DistributedLock lock;
	private final SchedulerProperties props;

	@Scheduled(fixedDelay = 10000)
	public void reap() {
		lock.runExclusively(RUNNING_LOCK_KEY, props.sweeper().lockTtl(),
			handle -> guarded("lease-expiry sweep", this::recoverExpiredLeases));
	}

	@Scheduled(fixedDelay = 10000)
	public void requeueQueuedJobsMissingFromRedis() {
		lock.runExclusively(DRIFT_LOCK_KEY, props.sweeper().lockTtl(),
			handle -> guarded("queue-drift sweep", () -> recoverQueuedJobsMissingFromRedis(handle)));
	}

	private static void guarded(String name, Runnable sweep) {
		try {
			sweep.run();
		} catch (Exception e) {
			log.error("Unexpected error during {}", name, e);
		}
	}

	// ------------------------------------------------------------ RUNNING jobs

	/**
	 * Lease expiry is the <em>only</em> trigger for recovering a RUNNING job. Expiry is judged by the
	 * database clock both here and in the conditional update, so neither JVM clock skew nor a lease renewed
	 * between the read and the update can cause a premature requeue. The heartbeat is consulted only to
	 * label why the lease expired (crashed worker vs. stuck/partitioned worker).
	 */
	void recoverExpiredLeases() {
		Map<String, Boolean> aliveByWorker = new HashMap<>();
		for (Job job : repo.findRunningWithExpiredLease()) {
			boolean heartbeatAlive = job.getWorkerId() != null
				&& aliveByWorker.computeIfAbsent(job.getWorkerId(), this::isWorkerAlive);
			if (heartbeatAlive) {
				recover(job, "lease expired", "lease_expired");
			} else {
				recover(job, "worker lost (lease expired)", "worker_lost");
			}
		}
	}

	private void recover(Job job, String reason, String metricReason) {
		boolean cancelled = job.isCancelRequested();
		boolean exhausted = !cancelled && job.getAttempts() >= job.getMaxAttempts();
		JobStatus target = cancelled ? JobStatus.CANCELLED : exhausted ? JobStatus.DEAD : JobStatus.QUEUED;
		String error = cancelled ? reason + "; cancelled" : exhausted ? reason + "; max attempts exhausted" : reason;
		int updated = repo.recoverExpired(job.getId(), job.getWorkerId(), job.getLeaseToken(), target.name(), error,
			target.isTerminal());
		if (updated != 1) {
			log.debug("Job {} changed state before it could be recovered; leaving it alone", job.getId());
			return;
		}
		long secondsSinceUpdate = job.getUpdatedAt() == null ? -1
			: Math.max(0, Duration.between(job.getUpdatedAt(), Instant.now()).toSeconds());
		if (cancelled) {
			queue.clearCancel(job.getId());
			metrics.counter("jobs_cancelled_total").increment();
			log.warn("Job {} cancelled: {} on worker {}", job.getId(), reason, job.getWorkerId());
		} else if (exhausted) {
			queue.toDead(job.getId());
			metrics.counter("jobs_completed_total", "status", "dead").increment();
			log.warn("Job {} moved to DEAD: {} on worker {} after {}/{} attempts", job.getId(), reason,
				job.getWorkerId(), job.getAttempts(), job.getMaxAttempts());
		} else {
			queue.enqueue(job.getId(), job.getPriority());
			log.warn("Re-enqueued job {}: {} (worker {}, {} seconds since last update)", job.getId(), reason,
				job.getWorkerId(), secondsSinceUpdate);
		}
		metrics.counter("jobs_recovered_total", "reason", metricReason).increment();
	}

	/** Heartbeat key exists: used for labelling and metrics only, never as a recovery trigger. */
	private boolean isWorkerAlive(String workerId) {
		return Boolean.TRUE.equals(redis.hasKey("worker:" + workerId));
	}

	// ------------------------------------------------------------ Redis drift

	/**
	 * Re-adds QUEUED/SCHEDULED jobs that Postgres has but Redis lost (Redis wipe, crash between DB commit and
	 * ZADD, worker died between pop and claim). Candidates are paged by id, checked in one Lua call
	 * per page, re-checked once after a single wait, then re-enqueued only if the row is unchanged.
	 */
	void recoverQueuedJobsMissingFromRedis(DistributedLock.Handle handle) {
		SchedulerProperties.Sweeper cfg = props.sweeper();
		Instant now = Instant.now();
		int exhausted = repo.markExhaustedQueuedJobsDead(now);
		if (exhausted > 0) {
			log.warn("Moved {} QUEUED job(s) with exhausted attempts to DEAD", exhausted);
		}

		List<Job> candidates = new ArrayList<>();
		for (JobStatus status : sweptStatuses()) {
			collectMissing(status, now.minus(cfg.staleAfter()), candidates, handle);
		}
		if (candidates.isEmpty()) {
			return;
		}

		sleep(cfg.recheckDelay());
		if (Thread.currentThread().isInterrupted()) {
			return;
		}

		int requeued = 0;
		for (int from = 0; from < candidates.size(); from += cfg.pageSize()) {
			List<Job> page = candidates.subList(from, Math.min(candidates.size(), from + cfg.pageSize()));
			List<UUID> stillMissing = queue.findMissing(page.stream().map(Job::getId).toList());
			Map<UUID, Job> byId = new HashMap<>();
			page.forEach(job -> byId.put(job.getId(), job));
			for (UUID id : stillMissing) {
				Job job = byId.get(id);
				if (repo.touchIfUnchanged(id, job.getStatus(), job.getUpdatedAt(), Instant.now()) == 1) {
					queue.enqueueAt(id, job.getPriority(), job.getRunAt());
					metrics.counter("jobs_recovered_total", "reason", "missing_from_queue").increment();
					requeued++;
				}
			}
			handle.renew();
		}
		if (requeued > 0) {
			log.warn("Re-enqueued {} job(s) missing from Redis", requeued);
		}
	}

	List<JobStatus> sweptStatuses() {
		return List.of(JobStatus.QUEUED, JobStatus.SCHEDULED);
	}

	private void collectMissing(JobStatus status, Instant updatedBefore, List<Job> into,
			DistributedLock.Handle handle) {
		SchedulerProperties.Sweeper cfg = props.sweeper();
		UUID after = MIN_UUID;
		while (into.size() < cfg.maxCandidates()) {
			List<Job> page = repo.findStalePage(status, updatedBefore, after, Limit.of(cfg.pageSize()));
			if (page.isEmpty()) {
				return;
			}
			Map<UUID, Job> byId = new HashMap<>();
			page.forEach(job -> byId.put(job.getId(), job));
			for (UUID missing : queue.findMissing(byId.keySet())) {
				if (into.size() < cfg.maxCandidates()) {
					into.add(byId.get(missing));
				}
			}
			after = page.getLast().getId();
			handle.renew();
			if (page.size() < cfg.pageSize()) {
				return;
			}
		}
	}

	private static void sleep(Duration duration) {
		try {
			TimeUnit.MILLISECONDS.sleep(duration.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
