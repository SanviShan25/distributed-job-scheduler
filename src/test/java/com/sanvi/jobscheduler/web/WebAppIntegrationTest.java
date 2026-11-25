package com.sanvi.jobscheduler.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.sanvi.jobscheduler.security.SecurityConfig;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;

/**
 * The web app served by Spring Boot: static files, the single-page-app fallback, cache and security headers,
 * and that backend 404s stay JSON. Uses the small fixture app in {@code src/test/resources/static}.
 */
class WebAppIntegrationTest extends AbstractIntegrationTest {
    private static final String INDEX_MARKER = "data-test=\"spa-index\"";

    @Autowired WebApplicationContext context;
    @Autowired MockMvc mvc;

    /** No API key, no session: a first-time visitor. */
    private MockMvc visitor;

    @BeforeEach
    void setUp() {
        visitor = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private ResultActions expectIndexHtml(ResultActions result) throws Exception {
        return result
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString(INDEX_MARKER)))
            .andExpect(header().string("Cache-Control", "no-cache"));
    }

    // ------------------------------------------------------------------ SPA fallback

    /** MockMvc does not follow forwards; a real server serves /index.html for "/". */
    @Test
    void homePageForwardsToIndexHtml() throws Exception {
        visitor.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"))
            .andExpect(header().string("Content-Security-Policy", SecurityConfig.WEBSITE_CSP));
        expectIndexHtml(visitor.perform(get("/index.html")));
    }

    @Test
    void clientRoutesGetIndexHtml() throws Exception {
        for (String path : new String[] {"/login", "/check-inbox", "/verify-email?token=abc", "/reset-password?token=x",
                "/forgot-password", "/dashboard", "/settings/account", "/monitors/" + UUID.randomUUID(), "/no/such/page",
                "/alert-recipients/other"}) {
            expectIndexHtml(visitor.perform(get(path)));
        }
        visitor.perform(head("/dashboard")).andExpect(status().isOk());
    }

    @Test
    void missingFilesAreNotFoundRatherThanIndexHtml() throws Exception {
        for (String path : new String[] {"/assets/app-Missing1.js", "/assets/chunk", "/robots.txt", "/x/y.png"}) {
            visitor.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
        }
    }

    // ------------------------------------------------------------------ backend paths stay the backend

    @Test
    void unknownApiPathsAreJsonNotFound() throws Exception {
        String email = "web-" + UUID.randomUUID() + "@example.com";
        String password = "correct horse battery";
        TestBrowser browser = new TestBrowser(mvc).fetchCsrf();
        browser.postJson("/api/auth/signup", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
            .andExpect(status().isCreated());
        browser.login(email, password).andExpect(status().isOk());

        browser.perform(get("/api/no-such-endpoint"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void backendPathsNeverGetTheWebsite() throws Exception {
        // Logged out: the PulseCheck API answers 401 JSON, not the app.
        visitor.perform(get("/api/no-such-endpoint"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        // Scheduler API with a key: a JSON 404.
        visitor.perform(get("/schedules/a/b/c").header("X-API-Key", "test-key"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        // Without a key the scheduler API and unknown actuator paths stay closed.
        for (String path : new String[] {"/jobs/whatever", "/schedules", "/actuator/env"}) {
            visitor.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void websitePathsAreReadOnly() throws Exception {
        visitor.perform(post("/dashboard")).andExpect(status().isUnauthorized());
        visitor.perform(post("/dashboard").header("X-API-Key", "test-key")).andExpect(status().isForbidden());
    }

    @Test
    void swaggerUiIsStillServed() throws Exception {
        visitor.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString(INDEX_MARKER))));
    }

    /**
     * The website chain permits every GET outside {@link WebAppPaths#BACKEND_PATTERNS}. A controller mapped
     * anywhere else would therefore be public, so every mapping must be under a backend pattern (or be one of
     * the alert-email link pages, which have their own public chain).
     */
    @Test
    void everyControllerMappingIsABackendPath() {
        List<String> outside = new ArrayList<>();
        context.getBeansOfType(RequestMappingHandlerMapping.class).values().forEach(mapping ->
            mapping.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().forEach(pattern -> {
                if (!WebAppPaths.isBackend(pattern) && !pattern.startsWith("/alert-recipients/")) {
                    outside.add(pattern);
                }
            })));
        assertThat(outside).isEmpty();
    }

    // ------------------------------------------------------------------ headers

    @Test
    void pagesHaveSecurityHeaders() throws Exception {
        for (String path : new String[] {"/index.html", "/verify-email", "/assets/app-T3st1234.js"}) {
            visitor.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.WEBSITE_CSP))
                .andExpect(header().string("Content-Security-Policy", allOf(
                    containsString("script-src 'self'"), containsString("frame-ancestors 'none'"),
                    not(containsString("unsafe-inline")))))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    @Test
    void hashedAssetsAreCachedForAYearAndEverythingElseRevalidates() throws Exception {
        visitor.perform(get("/assets/app-T3st1234.js"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("test asset")))
            .andExpect(header().string("Cache-Control", allOf(
                containsString("max-age=31536000"), containsString("public"), containsString("immutable"))));
        visitor.perform(get("/favicon.svg"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
            .andExpect(header().string("Cache-Control", "no-cache"));
        visitor.perform(get("/index.html"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void notFoundAssetsAreNotCached() throws Exception {
        visitor.perform(get("/assets/app-Missing1.js"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
