package com.sanvi.jobscheduler.pulsecheck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.sanvi.jobscheduler.support.CsrfTestSupport.validCsrf;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

/** The production limits: login 10/min per IP and 5/min per email; sign-up 5/hour per IP. */
@TestPropertySource(properties = "pulsecheck.auth.rate-limits-enabled=true")
class AuthRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from users");
    }

    private ResultActions login(String ip, String email) throws Exception {
        return mvc.perform(post("/api/auth/login").with(validCsrf()).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"wrong password!\"}"));
    }

    private ResultActions signup(String ip, String email) throws Exception {
        return mvc.perform(post("/api/auth/signup").with(validCsrf()).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"a long enough password\"}"));
    }

    @Test
    void tenLoginsPerMinutePerIp() throws Exception {
        for (int i = 0; i < 10; i++) {
            // Different emails, so only the per-IP limit is in play.
            login("203.0.113.7", "user" + i + "@example.com").andExpect(status().isUnauthorized());
        }

        login("203.0.113.7", "user99@example.com")
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.detail", containsString("Too many login attempts")));
        login("203.0.113.8", "user99@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    void fiveLoginsPerMinutePerEmail() throws Exception {
        for (int i = 0; i < 5; i++) {
            // Different IPs, so only the per-email limit is in play (and case does not matter).
            login("198.51.100." + i, i % 2 == 0 ? "victim@example.com" : "VICTIM@example.com")
                .andExpect(status().isUnauthorized());
        }

        String retryAfter = login("198.51.100.50", "victim@example.com")
            .andExpect(status().isTooManyRequests())
            .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);
        login("198.51.100.50", "someone-else@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    void fiveSignupsPerHourPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            signup("192.0.2.10", "new" + i + "@example.com").andExpect(status().isCreated());
        }

        String retryAfter = signup("192.0.2.10", "new5@example.com")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.detail", containsString("Too many sign-ups")))
            .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Integer.parseInt(retryAfter)).as("one token per 12 minutes").isBetween(600, 720);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(5);

        signup("192.0.2.11", "new5@example.com").andExpect(status().isCreated());
    }

    @Test
    void theApiKeyRateLimitForJobsIsUnaffected() throws Exception {
        for (int i = 0; i < 11; i++) {
            login("203.0.113.9", "x" + i + "@example.com");
        }
        // /jobs has its own per-key limit and its own security chain.
        mvc.perform(post("/jobs").header("X-API-Key", "test-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EMAIL\"}"))
            .andExpect(status().isCreated());
    }
}
