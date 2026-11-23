package com.sanvi.jobscheduler.pulsecheck.email;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.pulsecheck.RecordingEmailConfiguration;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Emails are SEND_EMAIL jobs: queued in the request's transaction, idempotent, and redacted when finished. */
@Import(RecordingEmailConfiguration.class)
class EmailJobIntegrationTest extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailOutbox outbox;
    @Autowired RecordingEmailConfiguration.RecordingEmailSender emails;
    @Autowired RecordingEmailConfiguration.EmailDelivery delivery;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        emails.clear();
    }

    private void signup(String email) throws Exception {
        new TestBrowser(mvc).fetchCsrf()
            .postJson("/api/auth/signup", "{\"email\":\"" + email + "\",\"password\":\"a long enough password\"}")
            .andExpect(status().isCreated());
    }

    private Map<String, Object> emailJob() {
        return jdbc.queryForMap("select * from jobs where type = 'SEND_EMAIL'");
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static JsonNode payload(Map<String, Object> job) {
        return JSON.readTree((String) job.get("payload"));
    }

    @Test
    void signupQueuesAVerificationEmailJobInsteadOfSendingInTheRequest() throws Exception {
        signup("queue@example.com");

        assertThat(emails.sentSoFar()).as("nothing is sent inside the HTTP request").isEmpty();
        Map<String, Object> job = emailJob();
        UUID tokenId = jdbc.queryForObject("select id from auth_tokens", UUID.class);
        assertThat(job.get("status")).isEqualTo("QUEUED");
        assertThat(job.get("idempotency_key")).isEqualTo("email:verify:" + tokenId);
        assertThat(redis.opsForZSet().score(JobQueue.READY, job.get("id").toString()))
            .as("pushed to Redis after commit").isNotNull();

        JsonNode payload = payload(job);
        assertThat(payload.path("template").asString()).isEqualTo("VERIFY_EMAIL");
        assertThat(payload.path("to").asString()).isEqualTo("queue@example.com");
        String secret = payload.path("secret").asString();
        assertThat(jdbc.queryForObject("select token_hash from auth_tokens", String.class))
            .as("the table only stores the token's hash").isEqualTo(sha256(secret));
    }

    @Test
    void theSecretIsRedactedWhenTheJobSucceeds() throws Exception {
        signup("success@example.com");
        String secret = payload(emailJob()).path("secret").asString();

        assertThat(delivery.deliverAll()).isEqualTo(1);

        Map<String, Object> job = emailJob();
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat((String) job.get("payload")).doesNotContain(secret).doesNotContain("\"secret\"");
        JsonNode payload = payload(job);
        assertThat(payload.path("secretRedacted").asBoolean()).isTrue();
        assertThat(payload.path("template").asString()).isEqualTo("VERIFY_EMAIL");
        assertThat(emails.sentSoFar()).hasSize(1);
        assertThat(emails.sentSoFar().getFirst().text()).contains(secret); // the user got the link
    }

    @Test
    void theSecretIsRedactedWhenTheJobGoesDeadOrIsCancelled() throws Exception {
        signup("dead@example.com");
        signup("cancelled@example.com");
        jdbc.update("update jobs set status = 'DEAD' where payload like '%dead@example.com%'");
        jdbc.update("update jobs set status = 'CANCELLED' where payload like '%cancelled@example.com%'");

        for (Map<String, Object> job : jdbc.queryForList("select * from jobs where type = 'SEND_EMAIL'")) {
            assertThat(payload(job).has("secret")).as(job.get("status").toString()).isFalse();
            assertThat(payload(job).path("secretRedacted").asBoolean()).isTrue();
        }
    }

    @Test
    void aRunningOrRetryingJobKeepsItsSecret() throws Exception {
        signup("retry@example.com");
        jdbc.update("update jobs set status = 'SCHEDULED' where type = 'SEND_EMAIL'");

        assertThat(payload(emailJob()).has("secret")).isTrue();
    }

    @Test
    void otherJobTypesAreNeverTouchedByTheRedaction() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into jobs (id, type, payload, status, run_at, created_at, updated_at) values "
            + "(?, 'EMAIL', '{\"secret\":\"keep me\"}', 'QUEUED', now(), now(), now())", id);
        jdbc.update("update jobs set status = 'SUCCEEDED' where id = ?", id);

        assertThat(jdbc.queryForObject("select payload from jobs where id = ?", String.class, id)).contains("keep me");
    }

    @Test
    void theSameIdempotencyKeyIsOnlyQueuedAndSentOnce() {
        EmailJob email = new EmailJob(EmailJob.Template.ALREADY_REGISTERED, "dup@example.com", Map.of(),
            "email:test:duplicate", null);

        assertThat(outbox.enqueue(email)).isTrue();
        assertThat(outbox.enqueue(email)).isFalse();

        assertThat(jdbc.queryForObject("select count(*) from jobs where type = 'SEND_EMAIL'", Integer.class)).isEqualTo(1);
        delivery.deliverAll();
        delivery.deliverAll();
        assertThat(emails.sentSoFar()).hasSize(1);
        assertThat(emails.sentSoFar().getFirst().idempotencyKey()).isEqualTo("email:test:duplicate");
    }

    @Test
    void repeatedSignupsForAnExistingAccountSendOneNoticePerHour() throws Exception {
        signup("owner@example.com");
        signup("OWNER@example.com");
        signup("owner@example.com");

        assertThat(jdbc.queryForList("select idempotency_key from jobs where type = 'SEND_EMAIL' order by created_at",
            String.class)).hasSize(2).last().asString().startsWith("email:already-registered:");
        assertThat(emails.to("owner@example.com")).extracting(EmailSender.Email::subject)
            .containsExactly("Verify your PulseCheck email address", "You already have a PulseCheck account");
    }

    @Test
    void forgotPasswordQueuesAResetEmailKeyedByToken() throws Exception {
        signup("forgot@example.com");
        delivery.deliverAll();

        new TestBrowser(mvc).fetchCsrf().postJson("/api/auth/forgot-password", "{\"email\":\"forgot@example.com\"}")
            .andExpect(status().isAccepted());

        UUID tokenId = jdbc.queryForObject("select id from auth_tokens where purpose = 'PASSWORD_RESET'", UUID.class);
        assertThat(jdbc.queryForObject("select idempotency_key from jobs where status = 'QUEUED' and type = 'SEND_EMAIL'",
            String.class)).isEqualTo("email:reset:" + tokenId);
        assertThat(emails.latestToken("forgot@example.com", "Reset your PulseCheck password")).isNotBlank();
    }

    @Test
    void aRequeuedJobWhoseSecretWasRedactedGoesStraightToDead() throws Exception {
        signup("requeued@example.com");
        delivery.deliverAll();
        jdbc.update("update jobs set status = 'QUEUED' where type = 'SEND_EMAIL'"); // e.g. a manual requeue

        delivery.deliverAll();

        Map<String, Object> job = emailJob();
        assertThat(job.get("status")).isEqualTo("DEAD");
        assertThat((String) job.get("last_error")).contains("already redacted");
        assertThat(emails.sentSoFar()).hasSize(1);
    }
}
