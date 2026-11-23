package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckApiTestConfiguration;
import com.sanvi.jobscheduler.pulsecheck.RecordingEmailConfiguration;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;
import com.sanvi.jobscheduler.support.TestBrowser;

/** Verification, password reset/change and account deletion, driven through the emails a user receives. */
@Import({RecordingEmailConfiguration.class, PulseCheckApiTestConfiguration.class})
class AccountEmailFlowsIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "erin@example.com";
    private static final String PASSWORD = "original password";
    private static final String VERIFY = "Verify your PulseCheck email";
    private static final String RESET = "Reset your PulseCheck password";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingEmailConfiguration.RecordingEmailSender emails;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        jdbc.update("delete from job_schedules");
        emails.clear();
    }

    private TestBrowser browser() throws Exception {
        return new TestBrowser(mvc).fetchCsrf();
    }

    private TestBrowser signedUp() throws Exception {
        TestBrowser browser = browser();
        browser.postJson("/api/auth/signup", "{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}")
            .andExpect(status().isCreated());
        return browser;
    }

    private TestBrowser loggedIn(String password) throws Exception {
        TestBrowser browser = browser();
        browser.login(EMAIL, password).andExpect(status().isOk());
        return browser;
    }

    private void verify(TestBrowser browser, String token, int expectedStatus) throws Exception {
        browser.postJson("/api/auth/verify", "{\"token\":\"" + token + "\"}").andExpect(status().is(expectedStatus));
    }

    // ------------------------------------------------------------------ verification

    @Test
    void signupEmailsAVerificationLinkAndStoresOnlyTheTokenHash() throws Exception {
        signedUp();

        var sent = emails.to(EMAIL);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().subject()).startsWith(VERIFY);
        assertThat(sent.getFirst().body()).contains("http://localhost:8080/verify-email?token=").contains("24 hours");
        String token = emails.latestToken(EMAIL, VERIFY);
        Map<String, Object> row = jdbc.queryForMap("select * from auth_tokens");
        assertThat(row.get("purpose")).isEqualTo("EMAIL_VERIFICATION");
        assertThat(row.get("token_hash")).isEqualTo(AuthTokenService.hash(token)).isNotEqualTo(token);
        Boolean about24h = jdbc.queryForObject("select expires_at between now() + interval '23 hours 59 minutes' "
            + "and now() + interval '24 hours' from auth_tokens", Boolean.class);
        assertThat(about24h).isTrue();
    }

    @Test
    void theVerificationLinkWorksOnce() throws Exception {
        TestBrowser browser = signedUp();
        String token = emails.latestToken(EMAIL, VERIFY);

        browser.postJson("/api/auth/verify", "{\"token\":\"" + token + "\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Your email address is verified."));
        verify(browser, token, 400);

        TestBrowser session = loggedIn(PASSWORD);
        session.perform(get("/api/auth/me")).andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    void expiredOrBogusVerificationLinksAreRejected() throws Exception {
        TestBrowser browser = signedUp();
        String token = emails.latestToken(EMAIL, VERIFY);
        jdbc.update("update auth_tokens set expires_at = now() - interval '1 second'");

        browser.postJson("/api/auth/verify", "{\"token\":\"" + token + "\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(AccountManagementService.INVALID_LINK));
        verify(browser, "not-a-real-token", 400);
        verify(browser, "", 400);
        assertThat(jdbc.queryForObject("select email_verified from users", Boolean.class)).isFalse();
    }

    @Test
    void signingUpAgainEmailsTheOwnerInsteadOfIssuingALink() throws Exception {
        signedUp();
        browser().postJson("/api/auth/signup", "{\"email\":\"ERIN@example.com\",\"password\":\"another password\"}")
            .andExpect(status().isCreated());

        assertThat(emails.to(EMAIL)).extracting(e -> e.subject())
            .containsExactly("Verify your PulseCheck email address", "You already have a PulseCheck account");
        assertThat(jdbc.queryForObject("select count(*) from auth_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void resendingSendsANewLinkAndTheOldOneStopsWorking() throws Exception {
        signedUp();
        String first = emails.latestToken(EMAIL, VERIFY);
        TestBrowser session = loggedIn(PASSWORD);

        session.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/auth/resend-verification"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message", containsString("new verification link")));
        String second = emails.latestToken(EMAIL, VERIFY);

        assertThat(second).isNotEqualTo(first);
        verify(session, first, 400);
        verify(session, second, 200);

        session.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/auth/resend-verification"))
            .andExpect(jsonPath("$.message", containsString("already verified")));
        assertThat(emails.to(EMAIL)).filteredOn(e -> e.subject().startsWith(VERIFY)).hasSize(2);
    }

    @Test
    void resendNeedsALogin() throws Exception {
        browser().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/auth/resend-verification"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void monitorsCanOnlyBeCreatedAfterVerifying() throws Exception {
        signedUp();
        TestBrowser session = loggedIn(PASSWORD);
        String monitor = "{\"name\":\"Shop\",\"url\":\"https://shop.test\",\"intervalSeconds\":300}";

        session.postJson("/api/monitors", monitor)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("Email not verified"))
            .andExpect(jsonPath("$.detail", containsString("verify your email address")));

        verify(session, emails.latestToken(EMAIL, VERIFY), 200);
        session.postJson("/api/monitors", monitor).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ password reset

    @Test
    void forgotPasswordAnswersTheSameWhetherOrNotTheAccountExists() throws Exception {
        signedUp();
        emails.clear();
        TestBrowser browser = browser();

        String known = browser.postJson("/api/auth/forgot-password", "{\"email\":\"Erin@Example.com\"}")
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknown = browser.postJson("/api/auth/forgot-password", "{\"email\":\"nobody@example.com\"}")
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String invalid = browser.postJson("/api/auth/forgot-password", "{\"email\":\"not an email\"}")
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(known).isEqualTo(unknown).isEqualTo(invalid);
        assertThat(emails.all()).hasSize(1);
        assertThat(emails.to(EMAIL).getFirst().body()).contains("/reset-password?token=").contains("60 minutes");
        Boolean aboutOneHour = jdbc.queryForObject("select expires_at between now() + interval '59 minutes' "
            + "and now() + interval '60 minutes' from auth_tokens where purpose = 'PASSWORD_RESET'", Boolean.class);
        assertThat(aboutOneHour).isTrue();
    }

    @Test
    void resettingThePasswordLogsOutEverySessionAndTheLinkWorksOnce() throws Exception {
        signedUp();
        TestBrowser laptop = loggedIn(PASSWORD);
        TestBrowser phone = loggedIn(PASSWORD);
        laptop.postJson("/api/auth/forgot-password", "{\"email\":\"" + EMAIL + "\"}");
        String token = emails.latestToken(EMAIL, RESET);

        TestBrowser stranger = browser();
        stranger.postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"brand new password\"}")
            .andExpect(status().isOk());

        laptop.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        phone.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        browser().login(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
        loggedIn("brand new password");

        stranger.postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"yet another password\"}")
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetLinksExpireAfterAnHour() throws Exception {
        signedUp();
        TestBrowser browser = browser();
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + EMAIL + "\"}");
        String token = emails.latestToken(EMAIL, RESET);
        jdbc.update("update auth_tokens set expires_at = now() - interval '1 second' where purpose = 'PASSWORD_RESET'");

        browser.postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"brand new password\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(AccountManagementService.INVALID_LINK));
        loggedIn(PASSWORD);
    }

    @Test
    void aNewResetRequestInvalidatesTheOlderLink() throws Exception {
        signedUp();
        TestBrowser browser = browser();
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + EMAIL + "\"}");
        String older = emails.latestToken(EMAIL, RESET);
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + EMAIL + "\"}");

        browser.postJson("/api/auth/reset-password", "{\"token\":\"" + older + "\",\"newPassword\":\"brand new password\"}")
            .andExpect(status().isBadRequest());
    }

    @Test
    void aTooShortNewPasswordDoesNotUseUpTheLink() throws Exception {
        signedUp();
        TestBrowser browser = browser();
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + EMAIL + "\"}");
        String token = emails.latestToken(EMAIL, RESET);

        browser.postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"short\"}")
            .andExpect(status().isBadRequest());
        browser.postJson("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"long enough now\"}")
            .andExpect(status().isOk());
    }

    @Test
    void resetWorksForALockedAccountAndUnlocksIt() throws Exception {
        signedUp();
        jdbc.update("update users set failed_login_count = 0, locked_until = now() + interval '15 minutes'");
        TestBrowser browser = browser();
        browser.postJson("/api/auth/forgot-password", "{\"email\":\"" + EMAIL + "\"}");

        browser.postJson("/api/auth/reset-password",
                "{\"token\":\"" + emails.latestToken(EMAIL, RESET) + "\",\"newPassword\":\"brand new password\"}")
            .andExpect(status().isOk());

        loggedIn("brand new password");
        assertThat(jdbc.queryForObject("select email_verified from users", Boolean.class))
            .as("following the emailed link proves the address").isTrue();
    }

    // ------------------------------------------------------------------ change password, delete account

    @Test
    void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
        signedUp();
        TestBrowser current = loggedIn(PASSWORD);
        TestBrowser other = loggedIn(PASSWORD);
        String before = current.sessionCookie();

        current.postJson("/api/auth/change-password", "{\"currentPassword\":\"wrong\",\"newPassword\":\"brand new password\"}")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("Your password is incorrect"));
        current.postJson("/api/auth/change-password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"brand new password\"}")
            .andExpect(status().isOk());

        current.perform(get("/api/auth/me")).andExpect(status().isOk());
        assertThat(current.sessionCookie()).as("session id rotated").isNotEqualTo(before);
        other.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        browser().login(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
        loggedIn("brand new password");
    }

    @Test
    void deletingTheAccountRemovesMonitorsSchedulesDataAndSessions() throws Exception {
        signedUp();
        verify(browser(), emails.latestToken(EMAIL, VERIFY), 200);
        TestBrowser session = loggedIn(PASSWORD);
        TestBrowser otherDevice = loggedIn(PASSWORD);
        session.postJson("/api/monitors", "{\"name\":\"Shop\",\"url\":\"https://shop.test\",\"intervalSeconds\":60}")
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from job_schedules", Integer.class)).isEqualTo(1);

        session.perform(delete("/api/auth/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong\"}"))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);

        session.perform(delete("/api/auth/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from monitors", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from job_schedules", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auth_tokens", Integer.class)).isZero();
        session.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        otherDevice.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        browser().login(EMAIL, PASSWORD).andExpect(status().isUnauthorized());
    }
}
