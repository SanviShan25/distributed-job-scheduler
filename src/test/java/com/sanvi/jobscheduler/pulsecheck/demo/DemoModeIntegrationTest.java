package com.sanvi.jobscheduler.pulsecheck.demo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.pulsecheck.MonitorCheckService;
import com.sanvi.jobscheduler.pulsecheck.PulseCheckApiTestConfiguration;
import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.email.EmailJob;
import com.sanvi.jobscheduler.pulsecheck.email.EmailOutbox;
import com.sanvi.jobscheduler.schedule.ScheduleRunner;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;
import com.sanvi.jobscheduler.worker.JobHandlerRegistry;

/**
 * Demo mode on, sign-ups limited to one address, and the real auth rate limits (demo logins capped at 3/hour here so
 * the limit is quick to reach). Own application context, so its demo account never meets the other tests.
 */
@Import(PulseCheckApiTestConfiguration.class)
@TestPropertySource(properties = {
    "pulsecheck.demo.enabled=true",
    "pulsecheck.signup.allowed-emails= Invited@Example.com , ",
    "pulsecheck.auth.rate-limits-enabled=true",
    "pulsecheck.auth.demo-logins-per-ip-per-hour=3"})
class DemoModeIntegrationTest extends AbstractIntegrationTest {

    private static final String DEMO_EMAIL = "demo@pulsecheck.invalid";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoAccount demo;
    @Autowired DemoStartup startup;
    @Autowired MonitorCheckService checks;
    @Autowired PulseCheckApiTestConfiguration.ScriptedSiteChecker sites;
    @Autowired EmailOutbox outbox;
    @Autowired ScheduleRunner scheduleRunner;
    @Autowired JobHandlerRegistry handlers;

    private TestBrowser browser;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from users where not demo");
        sites.reset();
        startup.onStartup();
        demo.reset();
        browser = new TestBrowser(mvc).fetchCsrf();
    }

    @AfterEach
    void tearDown() {
        sites.reset();
    }

    private UUID demoId() {
        return demo.userId().orElseThrow();
    }

    private ResultActions tryTheDemo() throws Exception {
        return browser.perform(post("/api/auth/demo"));
    }

    private int emailJobs() {
        return jdbc.queryForObject("select count(*) from jobs where type = 'SEND_EMAIL'", Integer.class);
    }

    private List<String> demoMonitorUrls() {
        return jdbc.queryForList("select url from monitors where user_id = ? order by url", String.class, demoId());
    }

    private UUID firstDemoMonitor() {
        return jdbc.queryForObject("select id from monitors where user_id = ? order by created_at, url limit 1",
            UUID.class, demoId());
    }

    // ------------------------------------------------------------------ the account and startup

    @Test
    void startupCreatesAVerifiedPasswordlessDemoAccountWithSampleMonitorsAndANightlyReset() {
        Map<String, Object> user = jdbc.queryForMap("select email, password_hash, email_verified from users where demo");
        assertThat(user.get("email")).isEqualTo(DEMO_EMAIL);
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("email_verified")).isEqualTo(true);
        assertThat(demoMonitorUrls()).containsExactly("https://example.com/",
            "https://raw.githubusercontent.com/SanviShan25/pulsecheck-test/main/status.txt");

        Map<String, Object> reset = jdbc.queryForMap(
            "select type, cron_expression, timezone, enabled, next_run_at from job_schedules where name = ?",
            DemoAccount.RESET_SCHEDULE_NAME);
        assertThat(reset.get("type")).isEqualTo(DemoResetJobHandler.TYPE);
        assertThat(reset.get("cron_expression")).isEqualTo("0 0 4 * * *");
        assertThat(reset.get("timezone")).isEqualTo("UTC");
        assertThat(reset.get("enabled")).isEqualTo(true);
        assertThat(reset.get("next_run_at")).isNotNull();
    }

    @Test
    void startupIsIdempotent() {
        startup.onStartup();
        startup.onStartup();
        assertThat(jdbc.queryForObject("select count(*) from users where demo", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where name = ?", Integer.class,
            DemoAccount.RESET_SCHEDULE_NAME)).isEqualTo(1);
        assertThat(demoMonitorUrls()).hasSize(2); // not seeded again: the account already has monitors
    }

    @Test
    void theDatabaseRefusesToGiveTheDemoAccountAPassword() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("update users set password_hash = '{argon2}x' where demo"))
            .hasMessageContaining("ck_users_demo_no_password");
    }

    @Test
    void startupRefusesWhenANormalAccountOwnsTheDemoAddress() {
        demo.removeAll(); // also deletes its monitors' schedules (not owned via a foreign key)
        jdbc.update("insert into users (id, email, email_verified) values (?, ?, true)", UUID.randomUUID(), DEMO_EMAIL);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> startup.onStartup())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already belongs to a normal account");
        jdbc.update("delete from users where email = ?", DEMO_EMAIL);
    }

    // ------------------------------------------------------------------ logging in

    @Test
    void tryTheDemoLogsInAsTheDemoAccountWithANewSessionAndCsrfToken() throws Exception {
        String csrfBefore = browser.cookies.get("XSRF-TOKEN");
        tryTheDemo().andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(DEMO_EMAIL))
            .andExpect(jsonPath("$.demo").value(true))
            .andExpect(jsonPath("$.emailVerified").value(true))
            .andExpect(jsonPath("$.monitorLimit").value(5))
            .andExpect(jsonPath("$.demoEnabled").value(true))
            .andExpect(jsonPath("$.signupOpen").value(false));
        String firstSession = browser.sessionCookie();
        assertThat(firstSession).isNotNull();
        assertThat(browser.cookies.get("XSRF-TOKEN")).isNotEqualTo(csrfBefore);

        browser.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.demo").value(true));
        browser.perform(get("/api/monitors")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        // Again from the same browser: the session id is rotated, and the old id no longer works.
        tryTheDemo().andExpect(status().isOk());
        assertThat(browser.sessionCookie()).isNotEqualTo(firstSession);
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie(TestBrowser.SESSION_COOKIE, firstSession)))
            .andExpect(status().isNoContent());
    }

    @Test
    void tryTheDemoNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/demo")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("csrf_invalid"));
    }

    @Test
    void tryTheDemoIsRateLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            tryTheDemo().andExpect(status().isOk());
        }
        tryTheDemo().andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        // Another visitor is not affected.
        TestBrowser other = new TestBrowser(mvc).fetchCsrf();
        other.perform(post("/api/auth/demo").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        })).andExpect(status().isOk());
    }

    @Test
    void theDemoAccountCannotLogInWithAPassword() throws Exception {
        browser.login(DEMO_EMAIL, "").andExpect(status().isUnauthorized());
        browser.login(DEMO_EMAIL, "any password at all").andExpect(status().isUnauthorized());
    }

    @Test
    void configIsPublic() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.demoEnabled").value(true))
            .andExpect(jsonPath("$.signupOpen").value(false))
            .andExpect(jsonPath("$.allowedEmails").doesNotExist());
    }

    // ------------------------------------------------------------------ restrictions

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

        assertThat(demo.userId()).isPresent();
        browser.perform(get("/api/auth/me")).andExpect(status().isOk()); // still logged in
        assertThat(emailJobs()).isZero();
    }

    @Test
    void extraAlertRecipientsCannotBeManaged() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        UUID monitor = firstDemoMonitor();
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
    void theDemoAccountMayHaveAtMostFiveMonitors() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        for (int i = 1; i <= 3; i++) {
            browser.postJson("/api/monitors", "{\"name\":\"Site " + i + "\",\"url\":\"https://site" + i
                + ".test/\",\"intervalSeconds\":60}").andExpect(status().isCreated());
        }
        browser.postJson("/api/monitors", "{\"name\":\"Six\",\"url\":\"https://six.test/\",\"intervalSeconds\":60}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("at most 5 monitors")));
        assertThat(demoMonitorUrls()).hasSize(5);
    }

    @Test
    void forgotPasswordForTheDemoAddressSendsNothing() throws Exception {
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + DEMO_EMAIL + "\"}")
            .andExpect(status().isAccepted());
        assertThat(emailJobs()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_tokens", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ no emails, ever

    @Test
    void outagesOfDemoMonitorsAreRecordedButNotEmailed() {
        UUID monitor = firstDemoMonitor();
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
    void theOutboxNeverQueuesEmailForTheDemoAddress() {
        boolean queued = outbox.enqueue(new EmailJob(EmailJob.Template.ALREADY_REGISTERED, "Demo@PulseCheck.invalid",
            Map.of(), "email:test:" + UUID.randomUUID(), null));
        assertThat(queued).isFalse();
        assertThat(emailJobs()).isZero();
    }

    // ------------------------------------------------------------------ nightly reset

    @Test
    void resetPutsBackTheSampleMonitorsAndDeletesEverythingElse() throws Exception {
        tryTheDemo().andExpect(status().isOk());
        browser.postJson("/api/monitors", "{\"name\":\"Mine\",\"url\":\"https://mine.test/\",\"intervalSeconds\":60}")
            .andExpect(status().isCreated());
        UUID sample = firstDemoMonitor();
        browser.perform(post("/api/monitors/" + sample + "/pause")).andExpect(status().isOk());
        checks.runCheck(jdbc.queryForObject("select id from monitors where url = 'https://mine.test/'", UUID.class));
        assertThat(jdbc.queryForObject("select count(*) from check_results", Integer.class)).isPositive();

        // A normal user's monitor must survive the reset.
        UUID other = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, 'other@example.com', true)", other);
        jdbc.update("insert into monitors (id, user_id, name, url, interval_seconds) values (?, ?, 'Other', "
            + "'https://other.test/', 60)", UUID.randomUUID(), other);

        handlers.find(DemoResetJobHandler.TYPE).orElseThrow().handle(null);
        handlers.find(DemoResetJobHandler.TYPE).orElseThrow().handle(null); // idempotent

        assertThat(demoMonitorUrls()).containsExactly("https://example.com/",
            "https://raw.githubusercontent.com/SanviShan25/pulsecheck-test/main/status.txt");
        assertThat(jdbc.queryForList("select enabled from monitors where user_id = ?", Boolean.class, demoId()))
            .containsOnly(true);
        assertThat(jdbc.queryForObject("select count(*) from check_results", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from monitors where user_id = ?", Integer.class, other))
            .isEqualTo(1);
        // Exactly one schedule per demo monitor, all enabled; the deleted monitors' schedules are gone.
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where name like 'monitor:%'", Integer.class))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from monitors m join job_schedules s on s.id = m.schedule_id "
            + "where m.user_id = ? and s.enabled and s.type = 'HTTP_CHECK' and s.payload = m.id::text", Integer.class,
            demoId())).isEqualTo(2);
        // Each sample monitor gets an immediate check.
        assertThat(jdbc.queryForObject("select count(*) from jobs where type = 'HTTP_CHECK' and status = 'QUEUED' "
            + "and payload in (select id::text from monitors where user_id = ?)", Integer.class, demoId()))
            .isEqualTo(2);

        // Visitors who were logged in stay logged in and see the fresh sample monitors.
        browser.perform(get("/api/monitors")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].url", not(org.hamcrest.Matchers.hasItem("https://mine.test/"))));
    }

    @Test
    void theNightlyResetRunsAsARecurringJobOnTheScheduler() throws Exception {
        jdbc.update("update job_schedules set next_run_at = now() - interval '1 second' where name = ?",
            DemoAccount.RESET_SCHEDULE_NAME);
        scheduleRunner.fireDue(Instant.now());
        Map<String, Object> run = jdbc.queryForMap("select type, status from jobs where cron_schedule_id = "
            + "(select id from job_schedules where name = ?)", DemoAccount.RESET_SCHEDULE_NAME);
        assertThat(run.get("type")).isEqualTo(DemoResetJobHandler.TYPE);
        assertThat(run.get("status")).isEqualTo("QUEUED");
        Instant next = jdbc.queryForObject("select next_run_at from job_schedules where name = ?", Instant.class,
            DemoAccount.RESET_SCHEDULE_NAME);
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
