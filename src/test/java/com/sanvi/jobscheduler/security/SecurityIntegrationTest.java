package com.sanvi.jobscheduler.security;

import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebApplicationContext context;

    /** Unlike the shared MockMvc, this one sends no API key unless asked to. */
    private MockMvc anonymous;

    @BeforeEach
    void setUp() {
        anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void jobsAndSchedulesRequireAnApiKey() throws Exception {
        for (String path : new String[] {"/jobs", "/jobs/stats", "/schedules"}) {
            anonymous.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString("X-API-Key")));
        }
        anonymous.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EMAIL\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyIsRejectedAndEveryConfiguredKeyWorks() throws Exception {
        anonymous.perform(get("/jobs/stats").header("X-API-Key", "nope")).andExpect(status().isUnauthorized());
        anonymous.perform(get("/jobs/stats").header("X-API-Key", "test-key")).andExpect(status().isOk());
        anonymous.perform(get("/jobs/stats").header("X-API-Key", "second-key")).andExpect(status().isOk());
    }

    @Test
    void healthAndPrometheusArePublic() throws Exception {
        anonymous.perform(get("/actuator/health")).andExpect(status().isOk());
        anonymous.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void openApiDocsArePublicAndDescribeTheApiKey() throws Exception {
        anonymous.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/jobs/{id}/cancel")))
            .andExpect(content().string(containsString("/schedules")))
            .andExpect(content().string(containsString("X-API-Key")));
    }

    /** GETs to unknown paths are the web app now (see WebAppIntegrationTest); anything else is still denied. */
    @Test
    void unknownPathsAreDenied() throws Exception {
        anonymous.perform(post("/internal/whatever").header("X-API-Key", "test-key"))
            .andExpect(status().isForbidden());
    }
}
