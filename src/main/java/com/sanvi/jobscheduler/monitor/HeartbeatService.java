package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.worker.WorkerLoop;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps {@code worker:<id>} alive in Redis. Runs in a lower lifecycle phase than {@link WorkerLoop}:
 * it starts before the worker threads and stops only after they have drained, so the Reaper never
 * mistakes a shutting-down worker with running jobs for a dead one.
 */
@Component
@Profile("worker")
@Slf4j
public class HeartbeatService implements SmartLifecycle {
	public static final int PHASE = WorkerLoop.PHASE - 100;
	private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(15);
	private static final long HEARTBEAT_INTERVAL_SECONDS = 5;

	private final StringRedisTemplate redis;
	private final String workerId;
	private ScheduledExecutorService heartbeatExecutor;
	private volatile boolean running;

	public HeartbeatService(StringRedisTemplate redis, @Value("${worker.id}") String workerId) {
		this.redis = redis;
		this.workerId = workerId;
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		heartbeat();
		heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "heartbeat-" + workerId);
			thread.setDaemon(true);
			return thread;
		});
		heartbeatExecutor.scheduleAtFixedRate(this::refreshHeartbeat, HEARTBEAT_INTERVAL_SECONDS,
			HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
		running = true;
	}

	public void heartbeat() {
		redis.opsForValue().set("worker:" + workerId, "alive", HEARTBEAT_TTL);
	}

	private void refreshHeartbeat() {
		try {
			heartbeat();
		} catch (RuntimeException e) {
			log.warn("Unable to refresh heartbeat for worker {}", workerId, e);
		}
	}

	@Override
	public synchronized void stop() {
		running = false;
		if (heartbeatExecutor != null) {
			heartbeatExecutor.shutdown();
			try {
				if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
					heartbeatExecutor.shutdownNow();
				}
			} catch (InterruptedException e) {
				heartbeatExecutor.shutdownNow();
				Thread.currentThread().interrupt();
			}
			heartbeatExecutor = null;
		}
		try {
			redis.delete("worker:" + workerId);
		} catch (RuntimeException e) {
			log.warn("Unable to delete heartbeat for worker {}; it will expire", workerId, e);
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int getPhase() {
		return PHASE;
	}
}
