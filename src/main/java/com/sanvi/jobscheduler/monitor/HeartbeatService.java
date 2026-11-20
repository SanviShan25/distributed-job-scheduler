package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Component
@Profile("worker")
@Slf4j
public class HeartbeatService {
	private static final Duration HEARTBEAT_TTL = Duration.ofSeconds(15);
	private static final long HEARTBEAT_INTERVAL_SECONDS = 5;

	private final StringRedisTemplate redis;
	private final String workerId;
	private ScheduledExecutorService heartbeatExecutor;

	public HeartbeatService(StringRedisTemplate redis, @Value("${worker.id}") String workerId) {
		this.redis = redis;
		this.workerId = workerId;
	}

	@PostConstruct
	public void start() {
		heartbeat();
		heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "heartbeat-" + workerId);
			thread.setDaemon(true);
			return thread;
		});
		heartbeatExecutor.scheduleAtFixedRate(this::refreshHeartbeat, HEARTBEAT_INTERVAL_SECONDS,
			HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
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

	@PreDestroy
	public void stop() {
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
		}
		redis.delete("worker:" + workerId);
	}
}
