package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

class SignupIntegrationTest extends AbstractIntegrationTest {

    private static final String GOOD_PASSWORD = "correct horse battery";

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwordEncoder;

    /** No API key, no session: sign-up must work for anonymous visitors. */
    private MockMvc anonymous;

    @BeforeEach
    void setUp() {
        anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from users");
    }

    private MvcResult signup(String email, String password, int expectedStatus) throws Exception {
        return anonymous.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    }

    private int userCount() {
        return jdbc.queryForObject("select count(*) from users", Integer.class);
    }

    @Test
    void signupCreatesAnUnverifiedAccountWithANormalisedEmail() throws Exception {
        anonymous.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  Alice@Example.COM \",\"password\":\"" + GOOD_PASSWORD + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("alice@example.com"))
            .andExpect(jsonPath("$.emailVerified").value(false));

        Map<String, Object> row = jdbc.queryForMap("select * from users");
        assertThat(row.get("email")).isEqualTo("alice@example.com");
        assertThat(row.get("email_verified")).isEqualTo(false);
        assertThat(row.get("failed_login_count")).isEqualTo(0);
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    void passwordIsStoredAsAnArgon2HashAndNeverReturned() throws Exception {
        MvcResult result = signup("hash@example.com", GOOD_PASSWORD, 201);

        String body = result.getResponse().getContentAsString();
        String hash = jdbc.queryForObject("select password_hash from users", String.class);
        assertThat(hash).startsWith("{argon2}$argon2id$").doesNotContain(GOOD_PASSWORD);
        assertThat(passwordEncoder.matches(GOOD_PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong password!", hash)).isFalse();
        assertThat(body).doesNotContain(hash).doesNotContain("argon2").doesNotContain(GOOD_PASSWORD)
            .doesNotContainIgnoringCase("password");
        assertThat(json.readTree(body).propertyNames()).containsExactlyInAnyOrder("email", "emailVerified", "message");
    }

    @Test
    void sameEmailInADifferentCaseGetsTheSameAnswerButNoSecondAccount() throws Exception {
        String first = signup("bob@example.com", GOOD_PASSWORD, 201).getResponse().getContentAsString();
        String originalHash = jdbc.queryForObject("select password_hash from users", String.class);

        String second = signup("BOB@Example.com", "a different password", 201).getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(userCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from users", String.class))
            .as("the existing account is untouched").isEqualTo(originalHash);
    }

    @Test
    void theDatabaseRejectsCaseVariantsEvenIfWrittenDirectly() {
        jdbc.update("insert into users (id, email) values (?, 'carol@example.com')", UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("insert into users (id, email) values (?, 'CAROL@example.com')",
            UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidEmailsAreRejected() throws Exception {
        for (String email : List.of("", "   ", "plainaddress", "@example.com", "user@", "user@example",
                "user name@example.com", "user@exa mple.com", "a@b@example.com", "user@-example.com",
                "x".repeat(250) + "@example.com")) {
            anonymous.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", email, "password", GOOD_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("valid email")));
        }
        assertThat(userCount()).isZero();
    }

    @Test
    void passwordMustBe10To128Characters() throws Exception {
        signup("short@example.com", "123456789", 400);
        signup("long@example.com", "x".repeat(129), 400);
        assertThat(userCount()).isZero();

        signup("min@example.com", "1234567890", 201);
        signup("max@example.com", "x".repeat(128), 201);
        assertThat(userCount()).isEqualTo(2);
    }

    @Test
    void longPasswordsAreNotTruncated() throws Exception {
        // BCrypt would ignore everything after 72 bytes; Argon2 does not.
        String base = "p".repeat(100);
        signup("trunc@example.com", base + "A", 201);
        String hash = jdbc.queryForObject("select password_hash from users", String.class);

        assertThat(passwordEncoder.matches(base + "A", hash)).isTrue();
        assertThat(passwordEncoder.matches(base + "B", hash)).isFalse();
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        anonymous.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        anonymous.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\"}"))
            .andExpect(status().isBadRequest());
    }
}
