package com.sanvi.jobscheduler.pulsecheck.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sanvi.jobscheduler.pulsecheck.MonitorCheckService;
import com.sanvi.jobscheduler.pulsecheck.PulseCheckApiTestConfiguration;
import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.email.EmailJob;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;
import com.sanvi.jobscheduler.schedule.ScheduleRunner;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;
import com.sanvi.jobscheduler.worker.JobHandlerRegistry;

import jakarta.servlet.http.Cookie;

/**
 * Demo mode on (at most 4 sandboxes here, so "busy" is quick to reach), sign-ups limited to one address, and the
 * real auth rate limits (demo logins capped at 3/hour per IP). Own application context.
 */
@Import(PulseCheckApiTestConfiguration.class)
@TestPropertySource(properties = {
    "pulsecheck.demo.enabled=true",
    "pulsecheck.demo.max-sandboxes=4",
    "pulsecheck.signup.allowed-emails= Invited@Example.com , ",
    "pulsecheck.auth.rate-limits-enabled=true",
    "pulsecheck.auth.demo-logins-per-ip-per-hour=3"})
class DemoModeIntegrationTest extends AbstractIntegrationTest {

    private static final String SAMPLE_A = "https://example.com/";
    private static final String SAMPLE_B = "https://raw.githubusercontent.com/SanviShan25/pulsecheck-test/main/status.txt";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoAccount demo;
    @Autowired DemoStartup startup;
    @Autowired MonitorCheckService checks;
    @Autowired PulseCheckApiTestConfiguration.ScriptedSiteChecker sites;
    @Autowired EmailOutbox outbox;
    @Autowired ScheduleRunner scheduleRunner;
    @Autowired JobHandlerRegistry handlers;

    private final AtomicInteger nextIp = new AtomicInteger(1);
    private TestBrowser browser;

    @BeforeEach
    void setUp() throws Exception {
        demo.removeAll();
        jdbc.update("delete from users");
        sites.reset();
        startup.onStartup();
        browser = new TestBrowser(mvc).fetchCsrf();
    }

    /** "Try the demo" from {@code b}, from its own client IP (so the per-IP limit only bites where intended). */
    private ResultActions tryTheDemo(TestBrowser b, String ip) throws Exception {
        return b.perform(post("/api/auth/demo").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }));
    }

    private ResultActions tryTheDemo() throws Exception {
        return tryTheDemo(browser, "198.51.100.1");
    }

    /** A new visitor (own browser, own IP) who starts a sandbox; returns its user id. */
    private UUID newVisitor() throws Exception {
        TestBrowser visitor = new TestBrowser(mvc).fetchCsrf();
        String body = tryTheDemo(visitor, "203.0.113." + nextIp.getAndIncrement()).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID sandboxOf(TestBrowser b) throws Exception {
        String body = b.perform(get("/api/auth/me")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private int sandboxes() {
        return jdbc.queryForObject("select count(*) from users where demo", Integer.class);
    }

    private int emailJobs() {
        return jdbc.queryForObject("select count(*) from jobs where type = 'SEND_EMAIL'", Integer.class);
    }

    private List<String> monitorUrls(UUID userId) {
        return jdbc.queryForList("select url from monitors where user_id = ? order by url", String.class, userId);
    }

    private UUID firstMonitor(UUID userId) {
        return jdbc.queryForObject("select id from monitors where user_id = ? order by url limit 1", UUID.class, userId);
    }

    private void expire(UUID userId) {
        jdbc.update("update users set demo_expires_at = now() - interval '1 second' where id = ?", userId);
    }

    // ------------------------------------------------------------------ startup

    @Test
    void startupSchedulesTheCleanupOnce() {
        startup.onStartup();
        Map<String, Object> cleanup = jdbc.queryForMap(
            "select type, cron_expression, timezone, enabled, next_run_at from job_schedules where name = ?",
            DemoAccount.CLEANUP_SCHEDULE_NAME);
        assertThat(cleanup.get("type")).isEqualTo(DemoCleanupJobHandler.TYPE);
        assertThat(cleanup.get("cron_expression")).isEqualTo("0 */5 * * * *");
        assertThat(cleanup.get("timezone")).isEqualTo("UTC");
        assertThat(cleanup.get("enabled")).isEqualTo(true);
        assertThat(cleanup.get("next_run_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where name = ?", Integer.class,
            DemoAccount.CLEANUP_SCHEDULE_NAME)).isEqualTo(1);
        assertThat(sandboxes()).isZero(); // sandboxes are made on demand, not at startup
    }

    @Test
    void theDatabaseKeepsSandboxesPasswordlessAndExpiring() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("insert into users (id, email, email_verified, demo, password_hash, "
            + "demo_expires_at) values (?, 'x@pulsecheck.invalid', true, true, '{argon2}x', now())", id))
            .hasMessageContaining("ck_users_demo_no_password");
        assertThatThrownBy(() -> jdbc.update("insert into users (id, email, email_verified, demo) "
            + "values (?, 'y@pulsecheck.invalid', true, true)", id))
            .hasMessageContaining("ck_users_demo_expiry");
    }

    // ------------------------------------------------------------------ creating a sandbox

    @Test
    void tryTheDemoCreatesAPrivateSandboxWithSampleMonitors() throws Exception {
        String csrfBefore = browser.cookies.get("XSRF-TOKEN");
        String body = tryTheDemo().andExpect(status().isOk())
            .andExpect(jsonPath("$.email", matchesPattern("demo-[0-9a-f-]{36}@pulsecheck\\.invalid")))
            .andExpect(jsonPath("$.demo").value(true))
            .andExpect(jsonPath("$.emailVerified").value(true))
            .andExpect(jsonPath("$.monitorLimit").value(3))
            .andExpect(jsonPath("$.minIntervalSeconds").value(300))
            .andExpect(jsonPath("$.demoEnabled").value(true))
            .andExpect(jsonPath("$.signupOpen").value(false))
            .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.demoExpiresAt")))
            .isCloseTo(Instant.now().plus(Duration.ofHours(3)), within(Duration.ofMinutes(1)));
        assertThat(browser.sessionCookie()).isNotNull();
        assertThat(browser.cookies.get("XSRF-TOKEN")).isNotEqualTo(csrfBefore);

        UUID id = sandboxOf(browser);
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, id)).isNull();
        assertThat(monitorUrls(id)).containsExactly(SAMPLE_A, SAMPLE_B);
        assertThat(jdbc.queryForList("select interval_seconds from monitors where user_id = ?", Integer.class, id))
            .containsOnly(300);
        assertThat(jdbc.queryForObject("select count(*) from monitors m join job_schedules s on s.id = m.schedule_id "
            + "where m.user_id = ? and s.enabled and s.type = 'HTTP_CHECK' and s.payload = m.id::text", Integer.class,
            id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from jobs where type = 'HTTP_CHECK' and status = 'QUEUED' "
            + "and payload in (select id::text from monitors where user_id = ?)", Integer.class, id)).isEqualTo(2);
        browser.perform(get("/api/monitors")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void visitorsCannotSeeEachOthersSandboxes() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        TestBrowser other = new TestBrowser(mvc).fetchCsrf();
        tryTheDemo(other, "203.0.113.200").andExpect(status().isOk());
        UUID mine = sandboxOf(browser);
        UUID theirs = sandboxOf(other);
        assertThat(mine).isNotEqualTo(theirs);

        browser.postJson("/api/monitors", "{\"name\":\"Mine\",\"url\":\"https://mine.test/\",\"intervalSeconds\":300}")
            .andExpect(status().isCreated());
        other.perform(get("/api/monitors")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        other.perform(get("/api/monitors/" + firstMonitor(mine))).andExpect(status().isNotFound());
    }

    @Test
    void tryingAgainStartsAFreshSandboxAndDeletesThePreviousOne() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        UUID first = sandboxOf(browser);
        String firstSession = browser.sessionCookie();

        tryTheDemo().andExpect(status().isOk());
        UUID second = sandboxOf(browser);
        assertThat(second).isNotEqualTo(first);
        assertThat(browser.sessionCookie()).isNotEqualTo(firstSession); // session id rotated
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, first)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where payload in "
            + "(select id::text from monitors where user_id = ?)", Integer.class, first)).isZero();
        assertThat(sandboxes()).isEqualTo(1);
        mvc.perform(get("/api/auth/me").cookie(new Cookie(TestBrowser.SESSION_COOKIE, firstSession)))
            .andExpect(status().isNoContent());
    }

    @Test
    void loggingOutDeletesTheSandbox() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        UUID id = sandboxOf(browser);
        browser.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        assertThat(sandboxes()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where name like 'monitor:%'", Integer.class))
            .isZero();
        assertThat(monitorUrls(id)).isEmpty();
    }

    @Test
    void tryTheDemoNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/demo")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
        assertThat(sandboxes()).isZero();
    }

    @Test
    void tryTheDemoIsRateLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            tryTheDemo().andExpect(status().isOk());
        }
        tryTheDemo().andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        newVisitor(); // another IP is not affected
    }

    @Test
    void whenAllSandboxesAreInUseTheDemoIsBusy() throws Exception {
        UUID oldest = newVisitor();
        for (int i = 0; i < 3; i++) {
            newVisitor();
        }
        TestBrowser late = new TestBrowser(mvc).fetchCsrf();
        tryTheDemo(late, "192.0.2.50").andExpect(status().isServiceUnavailable())
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("demo_busy"))
            .andExpect(jsonPath("$.detail").value("The demo is busy. Please try again in a few minutes."));
        assertThat(sandboxes()).isEqualTo(4);

        // An expired sandbox frees its place even before the cleanup has deleted it.
        expire(oldest);
        tryTheDemo(late, "192.0.2.50").andExpect(status().isOk());
    }

    @Test
    void anExpiredSandboxIsLoggedOutEvenBeforeCleanup() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        expire(sandboxOf(browser));
        browser.perform(get("/api/auth/me")).andExpect(status().isNoContent());
        browser.perform(get("/api/monitors")).andExpect(status().isUnauthorized());
    }

    @Test
    void sandboxesCannotLogInWithAPassword() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        String email = jdbc.queryForObject("select email from users where demo", String.class);
        TestBrowser stranger = new TestBrowser(mvc).fetchCsrf();
        stranger.login(email, "").andExpect(status().isUnauthorized());
        stranger.login(email, "any password at all").andExpect(status().isUnauthorized());
    }

    @Test
    void configIsPublic() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.demoEnabled").value(true))
            .andExpect(jsonPath("$.signupOpen").value(false))
            .andExpect(jsonPath("$.allowedEmails").doesNotExist());
    }

    // ------------------------------------------------------------------ restrictions and caps

    @Test
    void restrictedAccountActionsAreRefusedWithAClearMessage() throws Exception {
        tryTheDemo().andExpect(status().isOk());

        browser.postJson("/api/auth/change-password", "{\"currentPassword\":\"x\",\"newPassword\":\"a long enough password\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(DemoAccount.RESTRICTED_CODE))
            .andExpect(jsonPath("$.detail").value("The demo account can't change its password."));
        browser.perform(delete("/api/auth/account").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(DemoAccount.RESTRICTED_CODE));
        browser.perform(post("/api/auth/resend-verification"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(DemoAccount.RESTRICTED_CODE));

        assertThat(sandboxes()).isEqualTo(1);
        browser.perform(get("/api/auth/me")).andExpect(status().isOk()); // still logged in
        assertThat(emailJobs()).isZero();
    }

    @Test
    void extraAlertRecipientsCannotBeManaged() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        UUID monitor = firstMonitor(sandboxOf(browser));
        // A recipient that got there some other way (e.g. a database fix) still can't be resent or removed.
        UUID recipient = UUID.randomUUID();
        jdbc.update("insert into alert_recipients (id, monitor_id, email, status) values (?, ?, 'x@example.com', 'PENDING')",
            recipient, monitor);

        browser.postJson("/api/monitors/" + monitor + "/recipients", "{\"email\":\"friend@example.com\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(DemoAccount.RESTRICTED_CODE));
        browser.perform(post("/api/monitors/" + monitor + "/recipients/" + recipient + "/resend"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(DemoAccount.RESTRICTED_CODE));
        browser.perform(delete("/api/monitors/" + monitor + "/recipients/" + recipient))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(DemoAccount.RESTRICTED_CODE));
        assertThat(jdbc.queryForObject("select count(*) from alert_recipients where monitor_id = ?", Integer.class,
            monitor)).isEqualTo(1);
        assertThat(emailJobs()).isZero();
    }

    @Test
    void aSandboxMayHaveAtMostThreeMonitors() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        browser.postJson("/api/monitors", "{\"name\":\"Three\",\"url\":\"https://three.test/\",\"intervalSeconds\":300}")
            .andExpect(status().isCreated());
        browser.postJson("/api/monitors", "{\"name\":\"Four\",\"url\":\"https://four.test/\",\"intervalSeconds\":300}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("at most 3 monitors")));
        assertThat(monitorUrls(sandboxOf(browser))).hasSize(3);
    }

    @Test
    void sandboxMonitorsAreCheckedEveryFiveMinutesAtMost() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        browser.postJson("/api/monitors", "{\"name\":\"Fast\",\"url\":\"https://fast.test/\",\"intervalSeconds\":60}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("In the demo, websites can be checked at most every 5 minutes."));
        UUID sample = firstMonitor(sandboxOf(browser));
        browser.perform(patch("/api/monitors/" + sample).contentType(MediaType.APPLICATION_JSON)
            .content("{\"intervalSeconds\":60}")).andExpect(status().isBadRequest());
        browser.perform(patch("/api/monitors/" + sample).contentType(MediaType.APPLICATION_JSON)
            .content("{\"intervalSeconds\":900}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.intervalSeconds").value(900));
    }

    @Test
    void forgotPasswordForASandboxAddressSendsNothing() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        String email = jdbc.queryForObject("select email from users where demo", String.class);
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        assertThat(emailJobs()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_tokens", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ no emails, ever

    @Test
    void outagesOfSandboxMonitorsAreRecordedButNotEmailed() throws Exception {
        UUID monitor = firstMonitor(newVisitor());
        String url = jdbc.queryForObject("select url from monitors where id = ?", String.class, monitor);
        sites.script(url, HttpChecker.Outcome.down(500, 30, "HTTP 500"));
        for (int i = 0; i < MonitorCheckService.FAILURES_FOR_DOWN; i++) {
            checks.runCheck(monitor);
        }
        sites.script(url, HttpChecker.Outcome.up(200, 40));
        checks.runCheck(monitor);

        assertThat(jdbc.queryForObject("select count(*) from incidents where monitor_id = ? and resolved_at is not null",
            Integer.class, monitor)).isEqualTo(1);
        assertThat(jdbc.queryForList("select transition || ':' || outcome from alert_events where monitor_id = ? "
            + "order by created_at", String.class, monitor)).containsExactly("DOWN:DEMO", "UP:DEMO");
        assertThat(emailJobs()).isZero();
    }

    @Test
    void theOutboxNeverQueuesEmailForASandbox() throws Exception {
        UUID id = newVisitor();
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, id);
        boolean queued = outbox.enqueue(new EmailJob(EmailJob.Template.ALREADY_REGISTERED, email.toUpperCase(),
            Map.of(), "email:test:" + UUID.randomUUID(), null));
        assertThat(queued).isFalse();
        assertThat(emailJobs()).isZero();
    }

    // ------------------------------------------------------------------ cleanup

    @Test
    void cleanupDeletesExpiredSandboxesWithAllTheirDataAndNothingElse() throws Exception {
        UUID expired = newVisitor();
        UUID active = newVisitor();
        checks.runCheck(firstMonitor(expired));
        assertThat(jdbc.queryForObject("select count(*) from check_results", Integer.class)).isPositive();
        UUID normal = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, 'other@example.com', true)", normal);
        jdbc.update("insert into monitors (id, user_id, name, url, interval_seconds) values (?, ?, 'Other', "
            + "'https://other.test/', 60)", UUID.randomUUID(), normal);
        expire(expired);

        handlers.find(DemoCleanupJobHandler.TYPE).orElseThrow().handle(null);
        handlers.find(DemoCleanupJobHandler.TYPE).orElseThrow().handle(null); // idempotent

        assertThat(jdbc.queryForList("select id from users order by email", UUID.class))
            .containsExactlyInAnyOrder(active, normal);
        assertThat(monitorUrls(expired)).isEmpty();
        assertThat(monitorUrls(active)).hasSize(2);
        assertThat(monitorUrls(normal)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from check_results", Integer.class)).isZero();
        // Only the active sandbox's monitor schedules are left.
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where name like 'monitor:%'", Integer.class))
            .isEqualTo(2);
    }

    @Test
    void theCleanupRunsAsARecurringJobOnTheScheduler() {
        jdbc.update("update job_schedules set next_run_at = now() - interval '1 second' where name = ?",
            DemoAccount.CLEANUP_SCHEDULE_NAME);
        scheduleRunner.fireDue(Instant.now());
        Map<String, Object> run = jdbc.queryForMap("select type, status from jobs where cron_schedule_id = "
            + "(select id from job_schedules where name = ?)", DemoAccount.CLEANUP_SCHEDULE_NAME);
        assertThat(run.get("type")).isEqualTo(DemoCleanupJobHandler.TYPE);
        assertThat(run.get("status")).isEqualTo("QUEUED");
        Instant next = jdbc.queryForObject("select next_run_at from job_schedules where name = ?", Instant.class,
            DemoAccount.CLEANUP_SCHEDULE_NAME);
        assertThat(next).isAfter(Instant.now());
    }

    // ------------------------------------------------------------------ limited sign-ups

    @Test
    void signupIsLimitedToAllowedAddressesWithoutRevealingWhoHasAnAccount() throws Exception {
        jdbc.update("insert into users (id, email, email_verified) values (?, 'existing@example.com', true)",
            UUID.randomUUID());
        String body = "{\"email\":\"%s\",\"password\":\"a long enough password\"}";

        String forExisting = browser.postJson("/api/auth/signup", body.formatted("existing@example.com"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("signup_closed"))
            .andExpect(jsonPath("$.detail").value("Sign-ups are limited on this site, and this email address "
                + "isn't on the list. You can try the demo account instead."))
            .andReturn().getResponse().getContentAsString();
        String forNew = browser.postJson("/api/auth/signup", body.formatted("nobody@example.com"))
            .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(forNew).isEqualTo(forExisting);
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'nobody@example.com'", Integer.class))
            .isZero();

        // The allowed address (case-insensitive, list entries trimmed) signs up normally.
        browser.postJson("/api/auth/signup", body.formatted(" INVITED@example.com"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'invited@example.com'", Integer.class))
            .isEqualTo(1);
        assertThat(emailJobs()).isEqualTo(1); // only the invited address's verification email
    }
}
