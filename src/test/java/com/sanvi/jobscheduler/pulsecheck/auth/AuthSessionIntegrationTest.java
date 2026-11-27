package com.sanvi.jobscheduler.pulsecheck.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import jakarta.servlet.http.Cookie;

/** Real cookies through the real filters: Spring Session (Redis), CSRF and the session security chain. */
class AuthSessionIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a long enough password";
    private static final String SESSION_COOKIE = "PULSECHECK_SESSION";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired FindByIndexNameSessionRepository<? extends Session> sessions;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        accounts.signup("ann@example.com", PASSWORD);
    }

    /** A minimal browser: keeps cookies, and echoes the XSRF-TOKEN cookie in the X-XSRF-TOKEN header. */
    private final class Browser {
        final Map<String, String> cookies = new LinkedHashMap<>();
        MockHttpServletResponse last;

        ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
            cookies.forEach((name, value) -> request.cookie(new Cookie(name, value)));
            if (cookies.containsKey("XSRF-TOKEN")) {
                request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            ResultActions result = mvc.perform(request);
            last = result.andReturn().getResponse();
            for (String header : last.getHeaders("Set-Cookie")) {
                String[] nameValue = header.split(";", 2)[0].split("=", 2);
                if (header.contains("Max-Age=0") || nameValue[1].isEmpty()) {
                    cookies.remove(nameValue[0]);
                } else {
                    cookies.put(nameValue[0], nameValue[1]);
                }
            }
            return result;
        }

        Browser fetchCsrf() throws Exception {
            perform(get("/api/auth/csrf")).andExpect(status().isOk());
            return this;
        }

        ResultActions login(String email, String password, boolean rememberMe) throws Exception {
            return perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"rememberMe\":" + rememberMe + "}"));
        }

        String sessionCookie() {
            return cookies.get(SESSION_COOKIE);
        }

        String setCookieHeader(String name) {
            return last.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(name + "=")).findFirst().orElseThrow();
        }
    }

    private static String sessionId(String cookieValue) {
        return new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    private String annId() {
        return jdbc.queryForObject("select id::text from users where email = 'ann@example.com'", String.class);
    }

    // ------------------------------------------------------------------ the main flow

    @Test
    void loginThenMeThenLogoutThenMeIs401() throws Exception {
        Browser browser = new Browser().fetchCsrf();

        browser.login("Ann@Example.com", PASSWORD, false)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("ann@example.com"))
            .andExpect(jsonPath("$.emailVerified").value(false));
        assertThat(browser.sessionCookie()).isNotNull();

        browser.perform(get("/api/auth/me")).andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("ann@example.com"))
            .andExpect(jsonPath("$.monitorLimit").value(10))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String sessionBeforeLogout = browser.sessionCookie();
        browser.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

        browser.perform(get("/api/auth/me")).andExpect(status().isNoContent());
        // Even replaying the old cookie does not work: the session is gone from Redis.
        browser.cookies.put(SESSION_COOKIE, sessionBeforeLogout);
        browser.perform(get("/api/auth/me")).andExpect(status().isNoContent());
    }

    @Test
    void meWithoutASessionIs204NotAnError() throws Exception {
        new Browser().perform(get("/api/auth/me"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    @Test
    void otherEndpointsStill401WithoutASession() throws Exception {
        new Browser().perform(get("/api/monitors"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Please log in"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        Browser browser = new Browser().fetchCsrf();
        browser.login("ann@example.com", "the wrong password", false)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        browser.login("nobody@example.com", PASSWORD, false)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        assertThat(browser.sessionCookie()).as("no session for failed logins").isNull();
    }

    // ------------------------------------------------------------------ CSRF

    @Test
    void stateChangingRequestsWithoutTheCsrfTokenAreRejected() throws Exception {
        Browser browser = new Browser(); // never fetched a token
        browser.login("ann@example.com", PASSWORD, false)
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("csrf_invalid"))
            .andExpect(jsonPath("$.detail", containsString("CSRF")));
        // (The 403 above set an XSRF-TOKEN cookie, as every response does; a request sent without it fails too.)
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isForbidden());

        // Logged in, but a forged request carries the session cookie without the header.
        Browser loggedIn = new Browser().fetchCsrf();
        loggedIn.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());
        MockHttpServletRequestBuilder forged = post("/api/monitors").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"url\":\"https://example.com\",\"intervalSeconds\":60}")
            .cookie(new Cookie(SESSION_COOKIE, loggedIn.sessionCookie()));
        mvc.perform(forged).andExpect(status().isForbidden());
        // A wrong token is rejected too.
        mvc.perform(post("/api/auth/logout").cookie(new Cookie(SESSION_COOKIE, loggedIn.sessionCookie()))
                .cookie(new Cookie("XSRF-TOKEN", "attacker")).header("X-XSRF-TOKEN", "attacker-different"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
    }

    @Test
    void csrfEndpointReturnsTheHeaderNameAndTheCookieValue() throws Exception {
        Browser browser = new Browser();
        browser.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
            .andExpect(jsonPath("$.cookieName").value("XSRF-TOKEN"));
        String token = new tools.jackson.databind.ObjectMapper().readTree(browser.last.getContentAsString())
            .get("token").asString();
        assertThat(browser.cookies.get("XSRF-TOKEN")).isEqualTo(token);
        assertThat(browser.setCookieHeader("XSRF-TOKEN")).doesNotContain("HttpOnly");
    }

    @Test
    void loginIssuesAFreshCsrfToken() throws Exception {
        Browser browser = new Browser().fetchCsrf();
        String before = browser.cookies.get("XSRF-TOKEN");

        browser.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());

        assertThat(browser.cookies.get("XSRF-TOKEN")).isNotNull().isNotEqualTo(before);
        browser.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ session id rotation

    @Test
    void theOldSessionIdStopsWorkingAfterLogin() throws Exception {
        Browser browser = new Browser().fetchCsrf();
        browser.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());
        String oldSession = browser.sessionCookie();

        browser.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());
        String newSession = browser.sessionCookie();

        assertThat(newSession).isNotEqualTo(oldSession);
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SESSION_COOKIE, oldSession)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SESSION_COOKIE, newSession)))
            .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ cookie attributes and timeouts

    @Test
    void sessionCookieIsHttpOnlyLaxAndNotSecureOnLocalhost() throws Exception {
        Browser browser = new Browser().fetchCsrf();
        browser.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());

        String cookie = browser.setCookieHeader(SESSION_COOKIE);
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/")
            .doesNotContain("Secure").doesNotContain("Max-Age");
        Session session = sessions.findById(sessionId(browser.sessionCookie()));
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void sessionCookieIsSecureForRealHostNames() throws Exception {
        Browser browser = new Browser();
        browser.perform(get("/api/auth/csrf").with(request -> {
            request.setServerName("pulsecheck.example.com");
            return request;
        }));
        browser.perform(post("/api/auth/login").with(request -> {
            request.setServerName("pulsecheck.example.com");
            return request;
        }).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"ann@example.com\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());

        assertThat(browser.setCookieHeader(SESSION_COOKIE)).contains("Secure").contains("HttpOnly");
    }

    @Test
    void rememberMeKeepsTheSessionFor30Days() throws Exception {
        Browser browser = new Browser().fetchCsrf();
        browser.login("ann@example.com", PASSWORD, true).andExpect(status().isOk());

        assertThat(browser.setCookieHeader(SESSION_COOKIE)).contains("Max-Age=" + Duration.ofDays(30).toSeconds());
        Session session = sessions.findById(sessionId(browser.sessionCookie()));
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void sessionsLiveInRedisAndAreIndexedByUser() throws Exception {
        Browser laptop = new Browser().fetchCsrf();
        Browser phone = new Browser().fetchCsrf();
        laptop.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());
        phone.login("ann@example.com", PASSWORD, true).andExpect(status().isOk());

        assertThat(sessions.findByPrincipalName(annId())).hasSize(2)
            .containsKeys(sessionId(laptop.sessionCookie()), sessionId(phone.sessionCookie()));
        assertThat(redis.keys("pulsecheck:session:sessions:*")).isNotEmpty();
    }

    // ------------------------------------------------------------------ the two security worlds

    @Test
    void monitorsNeedALoggedInUserNotTheApiKey() throws Exception {
        // The shared MockMvc sends a valid X-API-Key on every request; that is not enough for /api.
        mvc.perform(get("/api/monitors")).andExpect(status().isUnauthorized());

        Browser browser = new Browser().fetchCsrf();
        browser.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());
        browser.perform(get("/api/monitors")).andExpect(result ->
            assertThat(result.getResponse().getStatus()).as("past authentication").isNotEqualTo(401));
    }

    @Test
    void schedulerEndpointsStillUseOnlyTheApiKey() throws Exception {
        mvc.perform(get("/jobs/stats")).andExpect(status().isOk()); // API key, no session, no CSRF
        mvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"EMAIL\"}"))
            .andExpect(status().isCreated());

        // A logged-in PulseCheck session does not grant access to the scheduler API.
        Browser browser = new Browser().fetchCsrf();
        browser.login("ann@example.com", PASSWORD, false).andExpect(status().isOk());
        mvc.perform(get("/jobs/stats").header("X-API-Key", "")
                .cookie(new Cookie(SESSION_COOKIE, browser.sessionCookie())))
            .andExpect(status().isUnauthorized());
    }
}
