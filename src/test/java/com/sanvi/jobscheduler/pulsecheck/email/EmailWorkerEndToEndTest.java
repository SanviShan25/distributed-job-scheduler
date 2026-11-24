package com.sanvi.jobscheduler.pulsecheck.email;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sanvi.jobscheduler.queue.DelayedJobMover;
import com.sanvi.jobscheduler.support.TestcontainersConfiguration;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

/**
 * Real worker profile with the Resend sender pointed at a local MockWebServer: the scheduler's retries,
 * backoff and DEAD handling apply to emails. The real Resend API is never called.
 */
@SpringBootTest(properties = {"worker.id=email-worker", "worker.concurrency=2", "worker.base-backoff-seconds=1",
    "worker.max-backoff-seconds=1", "pulsecheck.email.provider=resend", "pulsecheck.email.resend.api-key=re_test",
    "pulsecheck.email.from=PulseCheck <alerts@example.com>"})
@ActiveProfiles({"worker", "test"})
@Import(TestcontainersConfiguration.class)
@DirtiesContext
class EmailWorkerEndToEndTest {

    private static final MockWebServer RESEND = startResend();

    @Autowired EmailOutbox outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired DelayedJobMover mover;

    private static MockWebServer startResend() {
        try {
            MockWebServer server = new MockWebServer();
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void resendUrl(DynamicPropertyRegistry registry) {
        registry.add("pulsecheck.email.resend.base-url", () -> RESEND.url("/").toString());
    }

    @AfterAll
    static void stopResend() {
        RESEND.close();
    }

    @BeforeEach
    void drainRequests() throws Exception {
        while (RESEND.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // discard requests from earlier tests
        }
        jdbc.update("delete from jobs");
    }

    private static MockResponse resend(int code, String body) {
        return new MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build();
    }

    private Map<String, Object> awaitTerminal(String key) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            mover.moveNow(); // scheduling is off in tests: promote retries ourselves
            Map<String, Object> job = jdbc.queryForMap("select * from jobs where idempotency_key = ?", key);
            if (job.get("status").equals("SUCCEEDED") || job.get("status").equals("DEAD")) {
                return job;
            }
            Thread.sleep(100);
        }
        return jdbc.queryForMap("select * from jobs where idempotency_key = ?", key);
    }

    private static EmailJob verification(String key) {
        return new EmailJob(EmailJob.Template.VERIFY_EMAIL, "user@example.com", Map.of("ttlHours", "24"), key,
            "one-time-secret-token");
    }

    @Test
    void aRetryAfterA503DeliversTheEmailExactlyOnce() throws Exception {
        RESEND.enqueue(resend(503, "{\"statusCode\":503,\"name\":\"service_unavailable\",\"message\":\"try later\"}"));
        RESEND.enqueue(resend(200, "{\"id\":\"msg_1\"}"));

        outbox.enqueue(verification("email:verify:retry-test"));
        Map<String, Object> job = awaitTerminal("email:verify:retry-test");

        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("attempts")).isEqualTo(2);
        RecordedRequest first = RESEND.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = RESEND.takeRequest(1, TimeUnit.SECONDS);
        assertThat(RESEND.takeRequest(200, TimeUnit.MILLISECONDS)).as("no third send").isNull();
        assertThat(first.getHeaders().get("Idempotency-Key")).isEqualTo("email:verify:retry-test")
            .isEqualTo(second.getHeaders().get("Idempotency-Key"));
        assertThat(second.getBody().utf8()).contains("/verify-email?token=one-time-secret-token");
        assertThat((String) job.get("payload")).doesNotContain("one-time-secret-token").contains("secretRedacted");
    }

    @Test
    void aValidationErrorGoesStraightToDeadAndIsRedacted() throws Exception {
        RESEND.enqueue(resend(422, "{\"statusCode\":422,\"name\":\"validation_error\",\"message\":\"Invalid `to` field.\"}"));

        outbox.enqueue(verification("email:verify:dead-test"));
        Map<String, Object> job = awaitTerminal("email:verify:dead-test");

        assertThat(job.get("status")).isEqualTo("DEAD");
        assertThat(job.get("attempts")).isEqualTo(1);
        assertThat((String) job.get("last_error")).contains("422").contains("validation_error");
        assertThat((String) job.get("payload")).doesNotContain("one-time-secret-token").contains("secretRedacted");
        assertThat(RESEND.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
        assertThat(RESEND.takeRequest(200, TimeUnit.MILLISECONDS)).as("not retried").isNull();
    }
}
