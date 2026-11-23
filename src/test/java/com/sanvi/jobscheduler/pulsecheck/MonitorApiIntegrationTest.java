package com.sanvi.jobscheduler.pulsecheck;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static com.sanvi.jobscheduler.support.CsrfTestSupport.validCsrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.pulsecheck.api.MonitorSchedules;
import com.sanvi.jobscheduler.pulsecheck.auth.SessionUser;
import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.queue.JobQueue;
import com.sanvi.jobscheduler.schedule.ScheduleRunner;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

@Import(PulseCheckApiTestConfiguration.class)
class MonitorApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebApplicationContext context;
    /** Logged in as the dev user, with a valid CSRF token on every request. */
    MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired HttpCheckJobHandler handler;
    @Autowired ScheduleRunner scheduleRunner;
    @Autowired PulseCheckApiTestConfiguration.ScriptedSiteChecker sites;
    @Autowired PulseCheckProperties props;
    @Autowired CheckResultRetention retention;

    private UUID devUser;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        jdbc.update("delete from job_schedules");
        sites.reset();
        devUser = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, ?)", devUser, props.devUserEmail());
        var login = UsernamePasswordAuthenticationToken.authenticated(new SessionUser(devUser, props.devUserEmail()),
            null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(authentication(login)).with(validCsrf()))
            .build();
    }

    // ------------------------------------------------------------------ helpers

    private UUID create(String name, String url, int interval) throws Exception {
        String body = mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "url", url, "intervalSeconds", interval))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    /** Plays the worker: runs every queued HTTP_CHECK job and marks it done. Returns how many ran. */
    private int runQueuedChecks() throws Exception {
        List<Map<String, Object>> queued = jdbc.queryForList(
            "select id, payload from jobs where type = 'HTTP_CHECK' and status = 'QUEUED' order by created_at");
        for (Map<String, Object> job : queued) {
            handler.handle((String) job.get("payload"));
            jdbc.update("update jobs set status = 'SUCCEEDED', completed_at = now() where id = ?", job.get("id"));
        }
        return queued.size();
    }

    private int queuedChecksFor(UUID monitorId) {
        return jdbc.queryForObject("select count(*) from jobs where type = 'HTTP_CHECK' and status = 'QUEUED' "
            + "and payload = ?", Integer.class, monitorId.toString());
    }

    private Map<String, Object> scheduleOf(UUID monitorId) {
        return jdbc.queryForMap("select s.* from job_schedules s join monitors m on m.schedule_id = s.id where m.id = ?",
            monitorId);
    }

    private long resultsFor(UUID monitorId) {
        return jdbc.queryForObject("select count(*) from check_results where monitor_id = ?", Long.class, monitorId);
    }

    // ------------------------------------------------------------------ create -> check -> status

    @Test
    void createSchedulesChecksRunsOneImmediatelyAndShowsTheStatus() throws Exception {
        String body = mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Shop\",\"url\":\"https://shop.test\",\"intervalSeconds\":300,"
                    + "\"alertEmails\":[\"Owner@Example.com\"]}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", containsString("/api/monitors/")))
            .andExpect(jsonPath("$.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.url").value("https://shop.test/"))
            .andExpect(jsonPath("$.alertEmails[0]").value("owner@example.com"))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).get("id").asString());

        Map<String, Object> schedule = scheduleOf(id);
        assertThat(schedule.get("type")).isEqualTo("HTTP_CHECK");
        assertThat(schedule.get("payload")).isEqualTo(id.toString());
        assertThat(schedule.get("cron_expression")).isEqualTo(MonitorSchedules.cronFor(id, 300));
        assertThat(schedule.get("enabled")).isEqualTo(true);
        assertThat(schedule.get("next_run_at")).isNotNull();

        // The immediate check is a QUEUED job, pushed to Redis after the transaction committed.
        String jobId = jdbc.queryForObject("select id::text from jobs where payload = ?", String.class, id.toString());
        assertThat(redis.opsForZSet().score(JobQueue.READY, jobId)).isNotNull();

        assertThat(runQueuedChecks()).isEqualTo(1);

        mvc.perform(get("/api/monitors/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.lastCheckedAt").isNotEmpty())
            .andExpect(jsonPath("$.uptime.last24h").value(100.0))
            .andExpect(jsonPath("$.uptime.avgResponseMs24h").value(42))
            .andExpect(jsonPath("$.uptime.checks24h").value(1));
    }

    @Test
    void theScheduleKeepsCheckingAtTheInterval() throws Exception {
        UUID id = create("Blog", "https://blog.test", 60);
        runQueuedChecks();

        scheduleRunner.fireDue(Instant.now().plus(Duration.ofHours(2)));
        assertThat(runQueuedChecks()).isEqualTo(1);

        assertThat(resultsFor(id)).isEqualTo(2);
    }

    @Test
    void aDownSiteShowsDownWithAnIncidentAndCheckHistory() throws Exception {
        sites.script("https://broken.test/", HttpChecker.Outcome.down(500, 12, "HTTP 500"));
        UUID id = create("Broken", "https://broken.test", 60);
        runQueuedChecks();
        scheduleRunner.fireDue(Instant.now().plus(Duration.ofHours(2)));
        runQueuedChecks();

        mvc.perform(get("/api/monitors/{id}", id))
            .andExpect(jsonPath("$.status").value("DOWN"))
            .andExpect(jsonPath("$.consecutiveFailures").value(2))
            .andExpect(jsonPath("$.uptime.last24h").value(0.0));
        mvc.perform(get("/api/monitors/{id}/incidents", id))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].ongoing").value(true))
            .andExpect(jsonPath("$.content[0].cause").value("HTTP 500"));
        mvc.perform(get("/api/monitors/{id}/checks", id))
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].up").value(false))
            .andExpect(jsonPath("$.content[0].statusCode").value(500));
    }

    // ------------------------------------------------------------------ pause / resume / delete

    @Test
    void pauseStopsChecks() throws Exception {
        UUID id = create("Paused", "https://paused.test", 60);

        mvc.perform(post("/api/monitors/{id}/pause", id))
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.enabled").value(false));

        Map<String, Object> schedule = scheduleOf(id);
        assertThat(schedule.get("enabled")).isEqualTo(false);
        assertThat(schedule.get("next_run_at")).isNull();
        // The immediate check queued before pausing runs as a no-op...
        runQueuedChecks();
        // ...and the schedule creates nothing new.
        scheduleRunner.fireDue(Instant.now().plus(Duration.ofHours(2)));
        assertThat(queuedChecksFor(id)).isZero();
        assertThat(resultsFor(id)).isZero();
    }

    @Test
    void resumeRestartsChecksFromUnknown() throws Exception {
        UUID id = create("Resumed", "https://resumed.test", 60);
        runQueuedChecks();
        mvc.perform(post("/api/monitors/{id}/pause", id));

        mvc.perform(post("/api/monitors/{id}/resume", id))
            .andExpect(jsonPath("$.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.enabled").value(true));

        assertThat(scheduleOf(id).get("enabled")).isEqualTo(true);
        assertThat(scheduleOf(id).get("next_run_at")).isNotNull();
        assertThat(queuedChecksFor(id)).isEqualTo(1);
        runQueuedChecks();
        mvc.perform(get("/api/monitors/{id}", id)).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void deleteRemovesTheMonitorItsHistoryAndItsSchedule() throws Exception {
        UUID id = create("Gone", "https://gone.test", 60);
        runQueuedChecks();
        UUID scheduleId = (UUID) scheduleOf(id).get("id");
        scheduleRunner.fireDue(Instant.now().plus(Duration.ofHours(2))); // a check is queued but not yet run

        mvc.perform(delete("/api/monitors/{id}", id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/monitors/{id}", id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where id = ?", Integer.class, scheduleId))
            .isZero();
        assertThat(resultsFor(id)).isZero();
        assertThat(runQueuedChecks()).as("the leftover check runs as a quiet no-op").isEqualTo(1);
        assertThat(resultsFor(id)).isZero();
    }

    // ------------------------------------------------------------------ limits and validation

    @Test
    void theEleventhMonitorIsRejected() throws Exception {
        for (int i = 1; i <= 10; i++) {
            create("Site " + i, "https://site" + i + ".test", 3600);
        }

        mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"One too many\",\"url\":\"https://site11.test\",\"intervalSeconds\":3600}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail", containsString("at most 10")));
        assertThat(jdbc.queryForObject("select count(*) from monitors", Integer.class)).isEqualTo(10);
    }

    @Test
    void duplicateUrlForTheSameUserIsRejectedAfterNormalisation() throws Exception {
        create("Original", "https://dup.test/", 300);

        mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Copy\",\"url\":\"HTTPS://DUP.test\",\"intervalSeconds\":300}"))
            .andExpect(status().isConflict());
    }

    @Test
    void unsafeOrInvalidUrlsAreRejected() throws Exception {
        for (String url : List.of("http://169.254.169.254/latest/meta-data/", "http://localhost:8080/",
                "http://127.0.0.1/", "http://[::1]/", "http://internal.test/", "http://10.1.2.3/",
                "ftp://files.test/", "http://user:secret@site.test/", "not a url")) {
            mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "x", "url", url, "intervalSeconds", 60))))
                .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("select count(*) from monitors", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from job_schedules", Integer.class)).isZero();
    }

    @Test
    void onlyTheAllowedIntervalsAreAccepted() throws Exception {
        for (String interval : List.of("30", "120", "86400", "null")) {
            mvc.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"x\",\"url\":\"https://interval.test\",\"intervalSeconds\":" + interval + "}"))
                .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------ update

    @Test
    void changingTheIntervalUpdatesTheSchedule() throws Exception {
        UUID id = create("Interval", "https://interval.test", 60);

        mvc.perform(patch("/api/monitors/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"intervalSeconds\":900}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.intervalSeconds").value(900));

        assertThat(scheduleOf(id).get("cron_expression")).isEqualTo(MonitorSchedules.cronFor(id, 900));
    }

    @Test
    void changingTheUrlStartsOverAndChecksImmediately() throws Exception {
        sites.script("https://old.test/", HttpChecker.Outcome.down(503, 5, "HTTP 503"));
        UUID id = create("Moved", "https://old.test", 60);
        runQueuedChecks();
        scheduleRunner.fireDue(Instant.now().plus(Duration.ofHours(2)));
        runQueuedChecks();
        mvc.perform(get("/api/monitors/{id}", id)).andExpect(jsonPath("$.status").value("DOWN"));

        mvc.perform(patch("/api/monitors/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://new.test\"}"))
            .andExpect(jsonPath("$.status").value("UNKNOWN"))
            .andExpect(jsonPath("$.consecutiveFailures").value(0));

        mvc.perform(get("/api/monitors/{id}/incidents", id)).andExpect(jsonPath("$.content[0].ongoing").value(false));
        assertThat(queuedChecksFor(id)).isEqualTo(1);
        assertThat(scheduleOf(id).get("payload")).isEqualTo(id.toString());
    }

    // ------------------------------------------------------------------ uptime and history

    @Test
    void uptimeAndAverageResponseTimeAreComputedPerWindow() throws Exception {
        UUID id = create("Stats", "https://stats.test", 60);
        jdbc.update("delete from jobs");
        String insert = "insert into check_results (monitor_id, checked_at, up, response_ms) "
            + "values (?, now() - (? || ' minutes')::interval, ?, ?)";
        // last 24 h: 3 up (100/200/300 ms) + 1 down
        jdbc.update(insert, id, "10", true, 100);
        jdbc.update(insert, id, "20", true, 200);
        jdbc.update(insert, id, "30", true, 300);
        jdbc.update(insert, id, "40", false, 9000);
        // 3 days ago: 2 down
        jdbc.update(insert, id, String.valueOf(3 * 24 * 60), false, null);
        jdbc.update(insert, id, String.valueOf(3 * 24 * 60 + 1), false, null);
        // 20 days ago: 4 up
        for (int i = 0; i < 4; i++) {
            jdbc.update(insert, id, String.valueOf(20 * 24 * 60 + i), true, 50);
        }
        // 40 days ago: outside every window
        jdbc.update(insert, id, String.valueOf(40 * 24 * 60), false, null);

        mvc.perform(get("/api/monitors/{id}", id))
            .andExpect(jsonPath("$.uptime.last24h").value(75.0))      // 3 / 4
            .andExpect(jsonPath("$.uptime.last7d").value(50.0))       // 3 / 6
            .andExpect(jsonPath("$.uptime.last30d").value(70.0))      // 7 / 10
            .andExpect(jsonPath("$.uptime.avgResponseMs24h").value(200))
            .andExpect(jsonPath("$.uptime.checks24h").value(4));
    }

    @Test
    void uptimeIsNullBeforeTheFirstCheck() throws Exception {
        UUID id = create("Fresh", "https://fresh.test", 60);
        mvc.perform(get("/api/monitors/{id}", id))
            .andExpect(jsonPath("$.uptime.last24h").doesNotExist())
            .andExpect(jsonPath("$.uptime.checks24h").value(0));
    }

    @Test
    void checkHistoryIsPagedAndFilteredByTime() throws Exception {
        UUID id = create("History", "https://history.test", 60);
        jdbc.update("delete from jobs");
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        for (int i = 0; i < 7; i++) {
            jdbc.update("insert into check_results (monitor_id, checked_at, up) values (?, ?, true)", id,
                java.sql.Timestamp.from(base.plus(Duration.ofMinutes(i))));
        }

        mvc.perform(get("/api/monitors/{id}/checks", id).param("size", "3").param("page", "1"))
            .andExpect(jsonPath("$.totalElements").value(7))
            .andExpect(jsonPath("$.content.length()").value(3));
        mvc.perform(get("/api/monitors/{id}/checks", id)
                .param("from", base.plus(Duration.ofMinutes(2)).toString())
                .param("to", base.plus(Duration.ofMinutes(5)).toString()))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[0].checkedAt").value(containsString("00:04")));
        mvc.perform(get("/api/monitors/{id}/checks", id).param("size", "101")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ ownership, listing, retention

    @Test
    void listShowsOnlyMyMonitorsAndOthersAre404() throws Exception {
        UUID mine = create("Mine", "https://mine.test", 60);
        UUID otherUser = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        jdbc.update("insert into users (id, email) values (?, 'someone@else.test')", otherUser);
        jdbc.update("insert into monitors (id, user_id, name, url, interval_seconds) values (?, ?, 'x', "
            + "'https://theirs.test/', 60)", theirs, otherUser);

        mvc.perform(get("/api/monitors"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(mine.toString()));
        mvc.perform(get("/api/monitors/{id}", theirs)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/monitors/{id}", theirs)).andExpect(status().isNotFound());
        mvc.perform(get("/api/monitors/{id}/checks", theirs)).andExpect(status().isNotFound());
    }

    @Test
    void withoutTheDevUserTheApiExplainsWhatIsMissing() throws Exception {
        jdbc.update("delete from users");
        mvc.perform(get("/api/monitors"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.detail", containsString("dev")));
    }

    @Test
    void retentionDeletesOnlyResultsOlderThan90Days() throws Exception {
        UUID id = create("Old", "https://old-results.test", 60);
        jdbc.update("insert into check_results (monitor_id, checked_at, up) values (?, now() - interval '91 days', true)", id);
        jdbc.update("insert into check_results (monitor_id, checked_at, up) values (?, now() - interval '89 days', true)", id);

        assertThat(retention.deleteExpired(Instant.now(), null)).isEqualTo(1);
        assertThat(resultsFor(id)).isEqualTo(1);
    }
}
