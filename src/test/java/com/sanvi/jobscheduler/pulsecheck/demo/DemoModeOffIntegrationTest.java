package com.sanvi.jobscheduler.pulsecheck.demo;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.pulsecheck.auth.AccountService;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;

/** The default: demo mode off and sign-ups open (shared test context). */
class DemoModeOffIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accounts;
    @Autowired DemoStartup startup;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
    }

    @Test
    void tryTheDemoIsNotFound() throws Exception {
        new TestBrowser(mvc).fetchCsrf().perform(post("/api/auth/demo")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from users where demo", Integer.class)).isZero();
    }

    @Test
    void configSaysDemoOffAndSignupOpen() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.demoEnabled").value(false))
            .andExpect(jsonPath("$.signupOpen").value(true));
    }

    @Test
    void meShowsTheSameSwitchesForANormalAccount() throws Exception {
        accounts.signup("ann@example.com", "a long enough password");
        TestBrowser browser = new TestBrowser(mvc).fetchCsrf();
        browser.login("ann@example.com", "a long enough password").andExpect(status().isOk())
            .andExpect(jsonPath("$.demo").value(false))
            .andExpect(jsonPath("$.demoExpiresAt").doesNotExist())
            .andExpect(jsonPath("$.minIntervalSeconds").value(60))
            .andExpect(jsonPath("$.monitorLimit").value(10));
        browser.perform(get("/api/auth/me")).andExpect(status().isOk())
            .andExpect(jsonPath("$.demo").value(false))
            .andExpect(jsonPath("$.demoEnabled").value(false))
            .andExpect(jsonPath("$.signupOpen").value(true));
    }

    @Test
    void startupWithDemoModeOffRemovesLeftoverSandboxesAndTheCleanup() {
        UUID sandbox = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID monitorId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified, demo, demo_expires_at) "
            + "values (?, 'demo-x@pulsecheck.invalid', true, true, now() + interval '1 hour')", sandbox);
        jdbc.update("insert into job_schedules (id, name, cron_expression, type, payload, next_run_at) "
            + "values (?, ?, '0 * * * * *', 'HTTP_CHECK', ?, now())", scheduleId, "monitor:" + monitorId, monitorId.toString());
        jdbc.update("insert into monitors (id, user_id, name, url, interval_seconds, schedule_id) "
            + "values (?, ?, 'Demo', 'https://example.com/', 300, ?)", monitorId, sandbox, scheduleId);
        jdbc.update("insert into job_schedules (id, name, cron_expression, type, next_run_at) "
            + "values (?, ?, '0 */5 * * * *', 'DEMO_CLEANUP', now())", UUID.randomUUID(), DemoAccount.CLEANUP_SCHEDULE_NAME);
        UUID normal = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, 'ann@example.com', true)", normal);

        startup.onStartup();

        assertThat(jdbc.queryForObject("select count(*) from users where demo", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, normal)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from job_schedules where id = ? or name = ?", Integer.class,
            scheduleId, DemoAccount.CLEANUP_SCHEDULE_NAME)).isZero();
    }
}
