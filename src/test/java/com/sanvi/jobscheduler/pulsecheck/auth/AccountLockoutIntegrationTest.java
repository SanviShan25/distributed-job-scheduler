package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static com.sanvi.jobscheduler.support.CsrfTestSupport.validCsrf;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

/** Account lockout and uniform login failures (rate limits are off in this context). */
class AccountLockoutIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "locked@example.com";
    private static final String PASSWORD = "the right password";

    @Autowired WebApplicationContext context;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from users");
        accounts.signup(EMAIL, PASSWORD);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(validCsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andReturn();
    }

    private Map<String, Object> userRow() {
        return jdbc.queryForMap("select failed_login_count, locked_until, locked_until > now() as locked "
            + "from users where email = ?", EMAIL);
    }

    @Test
    void tenWrongPasswordsInARowLockTheAccountFor15Minutes() throws Exception {
        for (int i = 1; i <= 9; i++) {
            assertThat(login(EMAIL, "wrong password " + i).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(userRow().get("failed_login_count")).isEqualTo(9);
        assertThat(userRow().get("locked")).isNull();

        login(EMAIL, "wrong password 10");

        Map<String, Object> row = userRow();
        assertThat(row.get("locked")).isEqualTo(true);
        Boolean about15Minutes = jdbc.queryForObject("select locked_until between now() + interval '14 minutes' "
            + "and now() + interval '15 minutes' from users where email = ?", Boolean.class, EMAIL);
        assertThat(about15Minutes).isTrue();

        // Even the right password is refused while locked, with the usual message.
        MvcResult whileLocked = login(EMAIL, PASSWORD);
        assertThat(whileLocked.getResponse().getStatus()).isEqualTo(401);
        assertThat(whileLocked.getResponse().getContentAsString()).contains("Invalid email or password");
    }

    @Test
    void attemptsDuringTheLockDoNotExtendIt() throws Exception {
        for (int i = 0; i < 10; i++) {
            login(EMAIL, "wrong");
        }
        Object lockedUntil = userRow().get("locked_until");
        for (int i = 0; i < 5; i++) {
            login(EMAIL, "still wrong");
        }
        assertThat(userRow().get("locked_until")).isEqualTo(lockedUntil);
    }

    @Test
    void theAccountWorksAgainOnceTheLockExpiresAndSuccessResetsTheCounter() throws Exception {
        for (int i = 0; i < 10; i++) {
            login(EMAIL, "wrong");
        }
        jdbc.update("update users set locked_until = now() - interval '1 second' where email = ?", EMAIL);

        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> row = userRow();
        assertThat(row.get("failed_login_count")).isEqualTo(0);
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    void failuresMustBeConsecutive() throws Exception {
        for (int i = 0; i < 9; i++) {
            login(EMAIL, "wrong");
        }
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        for (int i = 0; i < 9; i++) {
            login(EMAIL, "wrong");
        }

        assertThat(userRow().get("locked")).isNull();
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void everyFailureLooksTheSame() throws Exception {
        String wrongPassword = login(EMAIL, "wrong password").getResponse().getContentAsString();
        String unknownEmail = login("nobody@example.com", PASSWORD).getResponse().getContentAsString();
        for (int i = 0; i < 10; i++) {
            login(EMAIL, "wrong");
        }
        String locked = login(EMAIL, PASSWORD).getResponse().getContentAsString();

        assertThat(wrongPassword).isEqualTo(unknownEmail).isEqualTo(locked)
            .contains("Invalid email or password");
    }

    @Test
    void unknownEmailsTakeAboutAsLongAsWrongPasswords() throws Exception {
        // Warm up (JIT, connection pool, Argon2 tables).
        for (int i = 0; i < 3; i++) {
            login("warmup@example.com", "x");
            login(EMAIL, "wrong-" + i);
        }
        jdbc.update("update users set failed_login_count = 0 where email = ?", EMAIL);

        long known = 0;
        long unknown = 0;
        int rounds = 6;
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime();
            login(EMAIL, "wrong password " + i);
            long t1 = System.nanoTime();
            login("nobody" + i + "@example.com", "wrong password " + i);
            long t2 = System.nanoTime();
            known += t1 - t0;
            unknown += t2 - t1;
        }
        // Without the dummy hash an unknown email would be an order of magnitude faster than a real check.
        assertThat((double) unknown / known).isBetween(0.5, 2.0);
    }
}
