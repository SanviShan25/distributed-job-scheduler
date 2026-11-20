package com.sanvi.jobscheduler.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.repo.JobRepository;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WorkerLoopTest {
    private static final String WORKER = "worker-test";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private JobQueue queue;
    private JobRepository repo;
    private SimpleMeterRegistry metrics;
    private WorkerProperties props;
    private final AtomicReference<UUID> claimedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        queue = mock(JobQueue.class);
        repo = mock(JobRepository.class);
        metrics = new SimpleMeterRegistry();
        props = props(30, 1);
    }

    private static WorkerProperties props(int leaseSeconds, int graceSeconds) {
        return new WorkerProperties(WORKER, 1, 300, leaseSeconds, graceSeconds, 1, 300, Duration.ofMillis(50));
    }

    /** Backoff stub always returns the ceiling so delays are deterministic. */
    private WorkerLoop loop(JobHandler... handlers) {
        return new WorkerLoop(queue, repo, new JobHandlerRegistry(List.of(handlers)), metrics,
            Timer.builder("job_duration").register(metrics), props,
            new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(300), bound -> bound - 1),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Job claimable(int attemptsAfterClaim, int maxAttempts) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType("TEST");
        job.setPayload("payload");
        job.setPriority(3);
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(attemptsAfterClaim);
        job.setMaxAttempts(maxAttempts);
        job.setTimeoutSeconds(300);
        when(repo.claim(eq(job.getId()), eq(WORKER), any(), any(), any())).thenAnswer(inv -> {
            claimedToken.set(inv.getArgument(2));
            job.setLeaseToken(inv.getArgument(2));
            return 1;
        });
        when(repo.findById(job.getId())).thenReturn(Optional.of(job));
        when(repo.markSucceeded(any(), any(), any(), any())).thenReturn(1);
        when(repo.markForRetry(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(repo.markDead(any(), any(), any(), any(), any())).thenReturn(1);
        when(repo.renewLease(any(), any(), any(), any(), any())).thenReturn(1);
        when(repo.releaseForShutdown(any(), any(), any(), any())).thenReturn(1);
        when(repo.markCancelled(any(), any(), any(), any())).thenReturn(1);
        return job;
    }

    private static JobHandler handler(ThrowingConsumer body) {
        return new JobHandler() {
            @Override
            public String type() {
                return "TEST";
            }

            @Override
            public void handle(String payload) throws Exception {
                body.accept(payload);
            }
        };
    }

    @FunctionalInterface
    interface ThrowingConsumer {
        void accept(String payload) throws Exception;
    }

    private double counter(String name, String... tags) {
        var counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void successMarksSucceededWithTheClaimToken() {
        Job job = claimable(1, 3);
        loop(handler(p -> { })).process(job.getId());

        verify(repo).markSucceeded(job.getId(), WORKER, claimedToken.get(), NOW);
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
        assertThat(counter("jobs_completed_total", "status", "success")).isEqualTo(1);
    }

    @Test
    void retryableFailureSchedulesRetryWithBackoff() {
        Job job = claimable(1, 3);
        loop(handler(p -> { throw new RuntimeException("boom"); })).process(job.getId());

        // attempt 1 -> ceiling min(300s, 1s * 2^1) = 2s
        Instant expectedRunAt = NOW.plusSeconds(2);
        verify(repo).markForRetry(job.getId(), WORKER, claimedToken.get(), "boom", expectedRunAt, NOW);
        verify(queue).enqueueDelayed(job.getId(), 3, expectedRunAt.toEpochMilli());
        verify(repo, never()).markDead(any(), any(), any(), any(), any());
    }

    @Test
    void failureOnLastAttemptMovesJobToDead() {
        Job job = claimable(3, 3);
        loop(handler(p -> { throw new RuntimeException("boom"); })).process(job.getId());

        verify(repo).markDead(job.getId(), WORKER, claimedToken.get(), "boom", NOW);
        verify(queue).toDead(job.getId());
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
        assertThat(counter("jobs_completed_total", "status", "dead")).isEqualTo(1);
    }

    @Test
    void nonRetryableExceptionGoesStraightToDead() {
        Job job = claimable(1, 5);
        loop(handler(p -> { throw new NonRetryableJobException("bad payload"); })).process(job.getId());

        verify(repo).markDead(job.getId(), WORKER, claimedToken.get(), "bad payload", NOW);
        verify(queue).toDead(job.getId());
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownHandlerTypeGoesStraightToDead() {
        Job job = claimable(1, 5);
        job.setType("NOPE");
        loop(handler(p -> { })).process(job.getId());

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repo).markDead(eq(job.getId()), eq(WORKER), eq(claimedToken.get()), error.capture(), eq(NOW));
        assertThat(error.getValue()).contains("no handler registered").contains("NOPE");
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
    }

    @Test
    void hungJobTimesOutIsInterruptedAndRetried() throws Exception {
        Job job = claimable(1, 3);
        job.setTimeoutSeconds(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        loop(handler(p -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        })).process(job.getId());

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("handler interrupted on timeout").isTrue();
        verify(repo).markForRetry(eq(job.getId()), eq(WORKER), eq(claimedToken.get()), eq("timeout"), any(), eq(NOW));
        assertThat(counter("jobs_timeout_total")).isEqualTo(1);
    }

    @Test
    void timeoutOnLastAttemptIsDead() {
        Job job = claimable(2, 2);
        job.setTimeoutSeconds(1);
        loop(handler(p -> Thread.sleep(30_000))).process(job.getId());

        verify(repo).markDead(job.getId(), WORKER, claimedToken.get(), "timeout", NOW);
    }

    @Test
    void nullExceptionMessageFallsBackToClassName() {
        Job job = claimable(1, 3);
        loop(handler(p -> { throw new IllegalStateException(); })).process(job.getId());

        verify(repo).markForRetry(eq(job.getId()), any(), any(), eq("java.lang.IllegalStateException"), any(), any());
    }

    @Test
    void longErrorMessagesAreTruncated() {
        Job job = claimable(3, 3);
        String huge = "x".repeat(5000);
        loop(handler(p -> { throw new RuntimeException(huge); })).process(job.getId());

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repo).markDead(any(), any(), any(), error.capture(), any());
        assertThat(error.getValue()).hasSize(WorkerLoop.MAX_ERROR_LENGTH);
    }

    @Test
    void unclaimableJobIsSkippedWithoutRunningTheHandler() {
        UUID id = UUID.randomUUID();
        when(repo.claim(any(), any(), any(), any(), any())).thenReturn(0);
        AtomicBoolean ran = new AtomicBoolean();
        loop(handler(p -> ran.set(true))).process(id);

        assertThat(ran).isFalse();
        verify(repo, never()).findById(any());
        assertThat(counter("jobs_duplicate_skipped_total")).isEqualTo(1);
    }

    @Test
    void leaseIsRenewedWhileAJobRuns() {
        props = props(1, 1); // lease 1s -> renew every ~333ms
        Job job = claimable(1, 3);
        loop(handler(p -> Thread.sleep(1_200))).process(job.getId());

        verify(repo, atLeast(2)).renewLease(eq(job.getId()), eq(WORKER), eq(claimedToken.get()), any(), any());
        verify(repo).markSucceeded(job.getId(), WORKER, claimedToken.get(), NOW);
    }

    @Test
    void lostLeaseCancelsTheHandlerAndReportsNothing() throws Exception {
        props = props(1, 1);
        Job job = claimable(1, 3);
        when(repo.renewLease(any(), any(), any(), any(), any())).thenReturn(0);
        CountDownLatch interrupted = new CountDownLatch(1);
        loop(handler(p -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        })).process(job.getId());

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        verify(repo, never()).markSucceeded(any(), any(), any(), any());
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
        verify(repo, never()).markDead(any(), any(), any(), any(), any());
    }

    @Test
    void cancelHintInRedisInterruptsTheHandlerAndMarksCancelled() throws Exception {
        Job job = claimable(1, 3);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(queue.isCancelRequested(job.getId())).thenAnswer(inv -> started.getCount() == 0);
        loop(handler(p -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        })).process(job.getId());

        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        verify(repo).markCancelled(job.getId(), WORKER, claimedToken.get(), NOW);
        verify(queue).clearCancel(job.getId());
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
        assertThat(counter("jobs_cancelled_total")).isEqualTo(1);
    }

    @Test
    void cancelFlagInDatabaseIsHonouredWhenRedisHintIsMissing() {
        props = props(1, 1);
        Job job = claimable(1, 3);
        job.setCancelRequested(true); // renewLease refuses because of the flag
        when(repo.renewLease(any(), any(), any(), any(), any())).thenReturn(0);
        loop(handler(p -> Thread.sleep(30_000))).process(job.getId());

        verify(repo).markCancelled(job.getId(), WORKER, claimedToken.get(), NOW);
    }

    @Test
    void shutdownLetsInFlightJobFinishWithinGrace() throws Exception {
        props = props(30, 5);
        Job job = claimable(1, 3);
        popOnce(job.getId());
        CountDownLatch started = new CountDownLatch(1);
        WorkerLoop loop = loop(handler(p -> {
            started.countDown();
            Thread.sleep(500);
        }));

        loop.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        loop.stop();

        verify(repo).markSucceeded(job.getId(), WORKER, claimedToken.get(), NOW);
        verify(repo, never()).releaseForShutdown(any(), any(), any(), any());
        assertThat(loop.isRunning()).isFalse();
    }

    @Test
    void shutdownInterruptAfterGraceReturnsJobWithoutChargingAnAttempt() throws Exception {
        props = props(30, 1);
        Job job = claimable(1, 3);
        popOnce(job.getId());
        CountDownLatch started = new CountDownLatch(1);
        WorkerLoop loop = loop(handler(p -> {
            started.countDown();
            Thread.sleep(30_000);
        }));

        loop.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        long stopStarted = System.nanoTime();
        loop.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - stopStarted)).isLessThan(Duration.ofSeconds(5));
        verify(repo).releaseForShutdown(job.getId(), WORKER, claimedToken.get(), NOW);
        verify(queue).enqueue(job.getId(), 3);
        verify(repo, never()).markForRetry(any(), any(), any(), any(), any(), any());
        verify(repo, never()).markDead(any(), any(), any(), any(), any());
    }

    @Test
    void stopHaltsPollingImmediately() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        when(queue.pop(any())).thenAnswer(inv -> {
            polls.incrementAndGet();
            Thread.sleep(20);
            return Optional.empty();
        });
        WorkerLoop loop = loop(handler(p -> { }));
        loop.start();
        verify(queue, timeout(2000).atLeast(2)).pop(any());
        loop.stop();
        int afterStop = polls.get();
        Thread.sleep(200);
        assertThat(polls.get()).isEqualTo(afterStop);
        verify(repo, never()).claim(any(), anyString(), any(), any(), any());
        verify(queue, never()).enqueueDelayed(any(), anyInt(), anyLong());
    }

    private void popOnce(UUID id) {
        AtomicBoolean handedOut = new AtomicBoolean();
        when(queue.pop(any())).thenAnswer(inv -> {
            if (handedOut.compareAndSet(false, true)) {
                return Optional.of(id);
            }
            Thread.sleep(20);
            return Optional.empty();
        });
    }
}
