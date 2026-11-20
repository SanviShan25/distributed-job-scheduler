package com.sanvi.jobscheduler.monitor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class HeartbeatServiceTest {

    @Test
    void heartbeatRemainsFreshWhileWorkerThreadRunsTwentySecondJob() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);

        AtomicLong expiresAtNanos = new AtomicLong();
        AtomicInteger heartbeatWrites = new AtomicInteger();
        doAnswer(invocation -> {
            Duration ttl = invocation.getArgument(2);
            expiresAtNanos.set(System.nanoTime() + ttl.toNanos());
            heartbeatWrites.incrementAndGet();
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        HeartbeatService heartbeatService = new HeartbeatService(redis, "worker-test");
        heartbeatService.start();

        CountDownLatch jobStarted = new CountDownLatch(1);
        CountDownLatch jobFinished = new CountDownLatch(1);
        Thread longRunningJob = new Thread(() -> {
            jobStarted.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                jobFinished.countDown();
            }
        }, "simulated-worker-job");

        try {
            longRunningJob.start();
            assertTrue(jobStarted.await(1, TimeUnit.SECONDS), "simulated job did not start");
            long testEndsAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < testEndsAt) {
                assertTrue(expiresAtNanos.get() > System.nanoTime(),
                    "worker heartbeat expired while the job was still running");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            assertTrue(jobFinished.await(1, TimeUnit.SECONDS), "simulated 20-second job did not finish");
            assertTrue(heartbeatWrites.get() >= 4,
                "expected immediate heartbeat plus periodic refreshes during the long job");
        } finally {
            longRunningJob.interrupt();
            heartbeatService.stop();
        }

        verify(redis).delete("worker:worker-test");
    }
}
