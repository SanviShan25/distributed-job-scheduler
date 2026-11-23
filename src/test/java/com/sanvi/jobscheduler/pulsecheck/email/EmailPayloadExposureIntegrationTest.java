package com.sanvi.jobscheduler.pulsecheck.email;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.pulsecheck.RecordingEmailConfiguration;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;
import com.sanvi.jobscheduler.worker.NonRetryableJobException;

/** A SEND_EMAIL payload (and its one-time token) must not leak through errors, logs or the admin API. */
@Import(RecordingEmailConfiguration.class)
class EmailPayloadExposureIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "TOP-SECRET-TOKEN-1234567890";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SendEmailJobHandler handler;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
    }

    private static String allMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            messages.append(t).append('\n');
        }
        return messages.toString();
    }

    @Test
    void aMalformedPayloadIsNotQuotedInTheJobError() {
        String malformed = "{\"template\":\"VERIFY_EMAIL\",\"secret\":\"" + SECRET + "\", oops";

        assertThatThrownBy(() -> handler.handle(malformed))
            .isInstanceOf(NonRetryableJobException.class)
            .satisfies(e -> {
                assertThat(allMessages(e)).doesNotContain(SECRET).contains("malformed SEND_EMAIL payload");
                assertThat(e.getCause()).as("no parser exception attached").isNull();
            });
    }

    @Test
    void aPayloadThatCannotBeRenderedIsNotQuotedEither() {
        String wrongTemplate = "{\"template\":\"NO_SUCH_TEMPLATE\",\"to\":\"a@example.com\",\"secret\":\"" + SECRET + "\"}";
        assertThatThrownBy(() -> handler.handle(wrongTemplate))
            .satisfies(e -> assertThat(allMessages(e)).doesNotContain(SECRET));
    }

    @Test
    void postgresErrorsDoNotCarryTheFailingRow() {
        // A CHECK violation: Postgres' DETAIL would normally quote the whole row, payload included.
        assertThatThrownBy(() -> jdbc.update("insert into jobs (id, type, payload, status, run_at, created_at, "
                + "updated_at) values (?, 'SEND_EMAIL', ?, 'BOGUS', now(), now(), now())",
                UUID.randomUUID(), "{\"secret\":\"" + SECRET + "\"}"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(e -> assertThat(allMessages(e)).doesNotContain(SECRET).contains("ck_jobs_status"));
    }

    @Test
    void theAdminJobsApiMasksAPendingEmailsToken() throws Exception {
        new TestBrowser(mvc).fetchCsrf()
            .postJson("/api/auth/signup", "{\"email\":\"api@example.com\",\"password\":\"a long enough password\"}")
            .andExpect(status().isCreated());
        String stored = jdbc.queryForObject("select payload from jobs where type = 'SEND_EMAIL'", String.class);
        String token = stored.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        UUID id = jdbc.queryForObject("select id from jobs where type = 'SEND_EMAIL'", UUID.class);

        String one = mvc.perform(get("/jobs/{id}", id)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String list = mvc.perform(get("/jobs").param("type", "SEND_EMAIL")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(stored).contains(token);
        assertThat(one).doesNotContain(token).contains("[redacted]").contains("VERIFY_EMAIL");
        assertThat(list).doesNotContain(token).contains("[redacted]");
    }

    @Test
    void otherJobTypesShowTheirPayloadUnchanged() throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/jobs")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\",\"payload\":\"{\\\"secret\\\":\\\"visible\\\"}\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("visible");
    }
}
