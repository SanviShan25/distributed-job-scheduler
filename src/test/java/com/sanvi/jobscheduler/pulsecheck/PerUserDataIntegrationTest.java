package com.sanvi.jobscheduler.pulsecheck;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.sanvi.jobscheduler.support.CsrfTestSupport.validCsrf;

import com.sanvi.jobscheduler.pulsecheck.auth.SessionUser;
import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.schedule.ScheduleRunner;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/** Every monitor, check and incident is visible and changeable only by its owner. */
@Import(PulseCheckApiTestConfiguration.class)
class PerUserDataIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired HttpCheckJobHandler handler;
    @Autowired ScheduleRunner scheduleRunner;
    @Autowired PulseCheckApiTestConfiguration.ScriptedSiteChecker sites;

    private MockMvc alice;
    private MockMvc bob;
    private UUID aliceMonitor;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from users");
        jdbc.update("delete from job_schedules");
        sites.reset();
        alice = loggedIn("alice@example.com");
        bob = loggedIn("bob@example.com");

        // Alice has a monitor with check history and an open incident.
        sites.script("https://alice-shop.test/", HttpChecker.Outcome.down(503, 20, "HTTP 503"));
        aliceMonitor = create(alice, "Alice's shop", "https://alice-shop.test");
        runQueuedChecks();
        scheduleRunner.fireDue(Instant.now().plus(Duration.ofHours(2)));
        runQueuedChecks();
    }

    private MockMvc loggedIn(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, ?, true)", id, email);
        var auth = UsernamePasswordAuthenticationToken.authenticated(new SessionUser(id, email), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(authentication(auth)).with(validCsrf()))
            .build();
    }

    private UUID create(MockMvc user, String name, String url) throws Exception {
        MvcResult result = user.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "url", url, "intervalSeconds", 60))))
            .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    private void runQueuedChecks() throws Exception {
        for (Map<String, Object> job : jdbc.queryForList(
                "select id, payload from jobs where type = 'HTTP_CHECK' and status = 'QUEUED'")) {
            handler.handle((String) job.get("payload"));
            jdbc.update("update jobs set status = 'SUCCEEDED' where id = ?", job.get("id"));
        }
    }

    @Test
    void aliceSeesHerOwnDataIncludingChecksAndIncidents() throws Exception {
        alice.perform(get("/api/monitors")).andExpect(jsonPath("$.length()").value(1));
        alice.perform(get("/api/monitors/{id}", aliceMonitor)).andExpect(jsonPath("$.status").value("DOWN"));
        alice.perform(get("/api/monitors/{id}/checks", aliceMonitor)).andExpect(jsonPath("$.totalElements").value(2));
        alice.perform(get("/api/monitors/{id}/incidents", aliceMonitor)).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void bobCannotSeeAlicesMonitorChecksOrIncidents() throws Exception {
        bob.perform(get("/api/monitors")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        bob.perform(get("/api/monitors/{id}", aliceMonitor)).andExpect(status().isNotFound());
        bob.perform(get("/api/monitors/{id}/checks", aliceMonitor)).andExpect(status().isNotFound());
        bob.perform(get("/api/monitors/{id}/incidents", aliceMonitor)).andExpect(status().isNotFound());
    }

    @Test
    void bobCannotEditPauseResumeOrDeleteAlicesMonitor() throws Exception {
        bob.perform(patch("/api/monitors/{id}", aliceMonitor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"hacked\",\"url\":\"https://evil.test\",\"intervalSeconds\":3600}"))
            .andExpect(status().isNotFound());
        bob.perform(post("/api/monitors/{id}/pause", aliceMonitor)).andExpect(status().isNotFound());
        bob.perform(post("/api/monitors/{id}/resume", aliceMonitor)).andExpect(status().isNotFound());
        bob.perform(delete("/api/monitors/{id}", aliceMonitor)).andExpect(status().isNotFound());

        Map<String, Object> row = jdbc.queryForMap("select * from monitors where id = ?", aliceMonitor);
        assertThat(row.get("name")).isEqualTo("Alice's shop");
        assertThat(row.get("url")).isEqualTo("https://alice-shop.test/");
        assertThat(row.get("interval_seconds")).isEqualTo(60);
        assertThat(row.get("enabled")).isEqualTo(true);
        assertThat(row.get("current_status")).isEqualTo("DOWN");
        assertThat(jdbc.queryForObject("select count(*) from check_results where monitor_id = ?", Integer.class,
            aliceMonitor)).isEqualTo(2);
    }

    @Test
    void someoneElsesMonitorLooksExactlyLikeOneThatDoesNotExist() throws Exception {
        String probedExisting = bob.perform(get("/api/monitors/{id}", aliceMonitor))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        UUID random = UUID.randomUUID();
        String probedMissing = bob.perform(get("/api/monitors/{id}", random))
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(probedExisting.replace(aliceMonitor.toString(), "ID"))
            .isEqualTo(probedMissing.replace(random.toString(), "ID"));
    }

    @Test
    void usersHaveSeparateUrlsAndLimits() throws Exception {
        // Bob may monitor the same URL as Alice.
        UUID bobsCopy = create(bob, "Also Alice's shop", "https://alice-shop.test");
        assertThat(bobsCopy).isNotEqualTo(aliceMonitor);

        // Alice filling her quota does not affect Bob.
        for (int i = 2; i <= 10; i++) {
            create(alice, "A" + i, "https://a" + i + ".test");
        }
        alice.perform(post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"url\":\"https://a11.test\",\"intervalSeconds\":60}"))
            .andExpect(status().isConflict());
        create(bob, "Bob's blog", "https://bob-blog.test");
        bob.perform(get("/api/monitors")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void anonymousUsersSeeNothing() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        anonymous.perform(get("/api/monitors")).andExpect(status().isUnauthorized());
        anonymous.perform(get("/api/monitors/{id}", aliceMonitor)).andExpect(status().isUnauthorized());
        anonymous.perform(get("/api/monitors/{id}/checks", aliceMonitor)).andExpect(status().isUnauthorized());
    }
}
