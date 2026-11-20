package com.sanvi.jobscheduler.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

@TestPropertySource(properties = {"security.rate-limit.capacity=3", "security.rate-limit.refill-per-second=0.01"})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired RedisRateLimiter limiter;

    @Test
    void submissionsBeyondTheBucketGet429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EMAIL\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(2 - i)));
        }
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EMAIL\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(repo.count()).isEqualTo(3);

        // Reads are not rate limited.
        mvc.perform(get("/jobs/stats")).andExpect(status().isOk());
    }

    @Test
    void bucketsArePerKeyAndRefillOverTime() throws Exception {
        assertThat(limiter.tryConsume("a", 2, 20).allowed()).isTrue();
        assertThat(limiter.tryConsume("a", 2, 20).allowed()).isTrue();
        RedisRateLimiter.Decision denied = limiter.tryConsume("a", 2, 20);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isBetween(1L, 50L);
        assertThat(limiter.tryConsume("b", 2, 20).allowed()).as("separate bucket").isTrue();

        Thread.sleep(120); // 20/s -> at least 2 tokens back
        assertThat(limiter.tryConsume("a", 2, 20).allowed()).isTrue();
    }
}
