package com.sanvi.jobscheduler.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/** Makes the shared MockMvc send a valid API key, so functional tests don't repeat the header. */
@TestConfiguration(proxyBeanMethods = false)
public class TestApiKeyConfiguration {
    public static final String API_KEY = "test-key";

    @Bean
    MockMvcBuilderCustomizer sendApiKeyByDefault() {
        return builder -> builder.defaultRequest(get("/").header("X-API-Key", API_KEY));
    }
}
