package com.sanvi.jobscheduler.pulsecheck.alert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static com.sanvi.jobscheduler.support.CsrfTestSupport.validCsrf;

import com.sanvi.jobscheduler.pulsecheck.MonitorCheckService;
import com.sanvi.jobscheduler.pulsecheck.RecordingEmailConfiguration;
import com.sanvi.jobscheduler.pulsecheck.auth.SessionUser;
import com.sanvi.jobscheduler.pulsecheck.check.HttpChecker;
import com.sanvi.jobscheduler.pulsecheck.check.SiteChecker;
import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;
import com.sanvi.jobscheduler.pulsecheck.domain.MonitorStatus;
import com.sanvi.jobscheduler.pulsecheck.email.EmailSender;
import com.sanvi.jobscheduler.pulsecheck.repo.CheckResultRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.IncidentRepository;
import com.sanvi.jobscheduler.pulsecheck.repo.MonitorRepository;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

@Import(RecordingEmailConfiguration.class)
class AlertRecipientIntegrationTest extends AbstractIntegrationTest {
    private static final String OWNER = "owner@example.com";
    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";
    private static final HttpChecker.Outcome UP = HttpChecker.Outcome.up(200, 40);
    private static final HttpChecker.Outcome DOWN = HttpChecker.Outcome.down(500, 30, "HTTP 500");

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitorRepository monitors;
    @Autowired CheckResultRepository results;
    @Autowired IncidentRepository incidents;
    @Autowired PlatformTransactionManager txManager;
    @Autowired AlertService alerts;
    @Autowired RecordingEmailConfiguration.RecordingEmailSender emails;

    /** Logged in as the owner. */
    private MockMvc mvc;
    /** No session, no CSRF token: what a recipient's browser or mail provider looks like. */
    private MockMvc anonymous;
    private UUID ownerId;
    private Monitor shop;
    private final Deque<HttpChecker.Outcome> script = new ArrayDeque<>();
    private MonitorCheckService checks;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        emails.clear();
        script.clear();
        ownerId = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, ?, true)", ownerId, OWNER);
        var login = UsernamePasswordAuthenticationToken.authenticated(new SessionUser(ownerId, OWNER), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/").with(authentication(login)).with(validCsrf())).build();
        anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        shop = monitor("Shop", "https://shop.example.com/");
        SiteChecker scripted = url -> script.isEmpty() ? UP : script.pop();
        checks = new MonitorCheckService(monitors, results, incidents, scripted, txManager, alerts);
    }

    // ------------------------------------------------------------------ helpers

    private Monitor monitor(String name, String url) {
        Instant now = Instant.now();
        Monitor monitor = new Monitor();
        monitor.setId(UUID.randomUUID());
        monitor.setUserId(ownerId);
        monitor.setName(name);
        monitor.setUrl(url);
        monitor.setIntervalSeconds(60);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UP);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return monitors.saveAndFlush(monitor);
    }

    private void check(Monitor monitor, HttpChecker.Outcome... outcomes) {
        for (HttpChecker.Outcome outcome : outcomes) {
            script.add(outcome);
            checks.runCheck(monitor.getId());
        }
    }

    private UUID addRecipient(Monitor monitor, String email) throws Exception {
        String body = mvc.perform(post("/api/monitors/{id}/recipients", monitor.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private String confirmationToken(String email) {
        return emails.latestToken(email, "Confirm PulseCheck alerts");
    }

    private void confirm(String token) throws Exception {
        anonymous.perform(post("/alert-recipients/confirm").param("token", token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Alerts confirmed")));
    }

    private UUID addConfirmed(Monitor monitor, String email) throws Exception {
        UUID id = addRecipient(monitor, email);
        confirm(confirmationToken(email));
        return id;
    }

    /** RFC 8058 one-click: what Gmail's "Unsubscribe" button sends. */
    private org.springframework.test.web.servlet.ResultActions oneClickUnsubscribe(String token) throws Exception {
        return anonymous.perform(post("/alert-recipients/unsubscribe").queryParam("token", token)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("List-Unsubscribe=One-Click"));
    }

    private String unsubscribeToken(String email, String subjectPrefix) {
        return emails.latestToken(email, subjectPrefix);
    }

    private String recipientStatus(UUID recipientId) {
        return jdbc.queryForObject("select status from alert_recipients where id = ?", String.class, recipientId);
    }

    private List<String> subjects(String email) {
        return emails.to(email).stream().map(EmailSender.Email::subject).toList();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ double opt-in

    @Test
    void pendingRecipientsGetOnlyTheConfirmationEmail() throws Exception {
        addRecipient(shop, "Alice@Example.com");

        List<EmailSender.Email> toAlice = emails.to(ALICE);
        assertThat(toAlice).hasSize(1);
        assertThat(toAlice.getFirst().subject()).isEqualTo("Confirm PulseCheck alerts for Shop");
        assertThat(toAlice.getFirst().text()).contains("https://shop.example.com/")
            .contains("http://localhost:8080/alert-recipients/confirm?token=").contains("72 hours");

        check(shop, DOWN, DOWN);
        check(shop, UP);

        assertThat(subjects(ALICE)).containsExactly("Confirm PulseCheck alerts for Shop");
        assertThat(subjects(OWNER)).containsExactly("Shop is DOWN", "Shop is back UP after less than a minute");
    }

    @Test
    void confirmedRecipientsGetAlertsWithUnsubscribeLinksAndHeaders() throws Exception {
        UUID alice = addRecipient(shop, ALICE);
        String token = confirmationToken(ALICE);

        // Opening the link (or a mail scanner prefetching it) changes nothing; the button POSTs.
        anonymous.perform(get("/alert-recipients/confirm").param("token", token))
            .andExpect(status().isOk()).andExpect(content().string(containsString("method=\"post\"")));
        assertThat(recipientStatus(alice)).isEqualTo("PENDING");
        confirm(token);

        mvc.perform(get("/api/monitors/{id}", shop.getId()))
            .andExpect(jsonPath("$.notifyOwner").value(true))
            .andExpect(jsonPath("$.recipients[0].email").value(ALICE))
            .andExpect(jsonPath("$.recipients[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.recipients[0].confirmedAt").isNotEmpty());
        mvc.perform(get("/api/monitors")).andExpect(jsonPath("$[0].recipients[0].status").value("CONFIRMED"));

        check(shop, DOWN, DOWN);

        UUID incidentId = jdbc.queryForObject("select id from incidents", UUID.class);
        assertThat(jdbc.queryForList("select idempotency_key from jobs where idempotency_key like 'alert:%' "
            + "order by idempotency_key", String.class))
            .containsExactly("alert:" + incidentId + ":down", "alert:" + incidentId + ":down:" + alice);

        for (String to : List.of(OWNER, ALICE)) {
            EmailSender.Email down = emails.to(to).getLast();
            assertThat(down.subject()).isEqualTo("Shop is DOWN");
            String link = "http://localhost:8080/alert-recipients/unsubscribe?token=" + unsubscribeToken(to, "Shop is DOWN");
            assertThat(down.text()).contains("Unsubscribe from alerts for this monitor: " + link);
            assertThat(down.html()).contains("href=\"" + link + "\"");
            assertThat(down.headers()).containsEntry("List-Unsubscribe", "<" + link + ">")
                .containsEntry("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        assertThat(unsubscribeToken(OWNER, "Shop is DOWN")).isNotEqualTo(unsubscribeToken(ALICE, "Shop is DOWN"));
    }

    // ------------------------------------------------------------------ unsubscribe

    @Test
    void unsubscribeWorksWithoutLoginIsIdempotentAndOnlyAffectsThatRecipientOnThatMonitor() throws Exception {
        Monitor blog = monitor("Blog", "https://blog.example.com/");
        UUID aliceOnShop = addConfirmed(shop, ALICE);
        UUID aliceOnBlog = addConfirmed(blog, ALICE);
        UUID bobOnShop = addConfirmed(shop, BOB);
        check(shop, DOWN, DOWN);
        String token = unsubscribeToken(ALICE, "Shop is DOWN");

        anonymous.perform(get("/alert-recipients/unsubscribe").param("token", token))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Unsubscribe")));
        assertThat(recipientStatus(aliceOnShop)).as("GET must not unsubscribe").isEqualTo("CONFIRMED");

        oneClickUnsubscribe(token).andExpect(status().isOk()).andExpect(content().string(containsString("Unsubscribed")));
        oneClickUnsubscribe(token).andExpect(status().isOk()).andExpect(content().string(containsString("Unsubscribed")));
        anonymous.perform(post("/alert-recipients/unsubscribe").param("token", token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(status().isOk());

        assertThat(recipientStatus(aliceOnShop)).isEqualTo("UNSUBSCRIBED");
        assertThat(recipientStatus(aliceOnBlog)).isEqualTo("CONFIRMED");
        assertThat(recipientStatus(bobOnShop)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select notify_owner from monitors where id = ?", Boolean.class, shop.getId()))
            .isTrue();

        emails.clear();
        check(shop, UP);
        check(blog, DOWN, DOWN);
        assertThat(subjects(ALICE)).containsExactly("Blog is DOWN");
        assertThat(subjects(BOB)).containsExactly("Shop is back UP after less than a minute");
        assertThat(subjects(OWNER)).containsExactlyInAnyOrder("Shop is back UP after less than a minute", "Blog is DOWN");

        mvc.perform(get("/api/monitors/{id}", shop.getId()))
            .andExpect(jsonPath("$.recipients[?(@.email == '" + ALICE + "')].status").value("UNSUBSCRIBED"));
    }

    @Test
    void theOwnerCanUnsubscribeFromOneMonitorAndTurnAlertsBackOn() throws Exception {
        check(shop, DOWN, DOWN);
        oneClickUnsubscribe(unsubscribeToken(OWNER, "Shop is DOWN")).andExpect(status().isOk());

        mvc.perform(get("/api/monitors/{id}", shop.getId())).andExpect(jsonPath("$.notifyOwner").value(false));
        emails.clear();
        check(shop, UP);
        assertThat(emails.to(OWNER)).isEmpty();

        mvc.perform(patch("/api/monitors/{id}", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notifyOwner\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.notifyOwner").value(true));
        check(shop, DOWN, DOWN);
        assertThat(subjects(OWNER)).containsExactly("Shop is DOWN");
    }

    // ------------------------------------------------------------------ tokens

    @Test
    void tokensAreHashedAndSingleUse() throws Exception {
        UUID alice = addRecipient(shop, ALICE);
        String confirmToken = confirmationToken(ALICE);

        String storedHash = jdbc.queryForObject("select token_hash from alert_recipient_tokens where purpose = 'CONFIRM'",
            String.class);
        assertThat(storedHash).isEqualTo(sha256(confirmToken)).doesNotContain(confirmToken);
        assertThat(jdbc.queryForObject("select count(*) from alert_recipient_tokens where token_hash = ?",
            Integer.class, confirmToken)).isZero();

        confirm(confirmToken);
        anonymous.perform(post("/alert-recipients/confirm").param("token", confirmToken))
            .andExpect(status().isGone());
        anonymous.perform(post("/alert-recipients/confirm").param("token", "not-a-real-token"))
            .andExpect(status().isNotFound());

        // Unsubscribe links: hashed, and once used they stay used even after re-subscribing.
        check(shop, DOWN, DOWN);
        String unsubscribe = unsubscribeToken(ALICE, "Shop is DOWN");
        assertThat(jdbc.queryForObject("select count(*) from alert_recipient_tokens where token_hash = ?",
            Integer.class, sha256(unsubscribe))).isOne();
        oneClickUnsubscribe(unsubscribe).andExpect(status().isOk());
        assertThat(recipientStatus(alice)).isEqualTo("UNSUBSCRIBED");

        addRecipient(shop, ALICE); // the owner asks again; Alice must opt in again
        assertThat(recipientStatus(alice)).isEqualTo("PENDING");
        confirm(confirmationToken(ALICE));
        oneClickUnsubscribe(unsubscribe).andExpect(status().isGone());
        assertThat(recipientStatus(alice)).isEqualTo("CONFIRMED");

        // No raw token is stored in any finished email job either (V9 redaction).
        emails.all(); // delivers every queued email job, which finishes (and redacts) it
        assertThat(jdbc.queryForObject("select count(*) from jobs where payload like ? or payload like ?",
            Integer.class, "%" + confirmToken + "%", "%" + unsubscribe + "%")).isZero();
    }

    @Test
    void resendingAConfirmationReplacesTheOldLink() throws Exception {
        UUID alice = addRecipient(shop, ALICE);
        String first = confirmationToken(ALICE);

        mvc.perform(post("/api/monitors/{id}/recipients/{rid}/resend", shop.getId(), alice))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        String second = confirmationToken(ALICE);
        assertThat(second).isNotEqualTo(first);

        anonymous.perform(post("/alert-recipients/confirm").param("token", first)).andExpect(status().isGone());
        confirm(second);
        mvc.perform(post("/api/monitors/{id}/recipients/{rid}/resend", shop.getId(), alice))
            .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ limits and validation

    @Test
    void aMonitorHasAtMostFiveExtraRecipients() throws Exception {
        for (int i = 1; i <= 5; i++) {
            addRecipient(shop, "r" + i + "@example.com");
        }
        mvc.perform(post("/api/monitors/{id}/recipients", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"r6@example.com\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(containsString("at most 5")));

        // Unsubscribed recipients don't count towards the limit.
        UUID r1 = jdbc.queryForObject("select id from alert_recipients where email = 'r1@example.com'", UUID.class);
        jdbc.update("update alert_recipients set status = 'UNSUBSCRIBED' where id = ?", r1);
        addRecipient(shop, "r6@example.com");

        UUID r2 = jdbc.queryForObject("select id from alert_recipients where email = 'r2@example.com'", UUID.class);
        mvc.perform(delete("/api/monitors/{id}/recipients/{rid}", shop.getId(), r2)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from alert_recipients where id = ?", Integer.class, r2)).isZero();
    }

    @Test
    void invalidDuplicateAndOwnAddressesAreRejected() throws Exception {
        addRecipient(shop, ALICE);
        for (String email : List.of("not-an-email", "ALICE@example.com", OWNER)) {
            mvc.perform(post("/api/monitors/{id}/recipients", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().is4xxClientError());
        }
        mvc.perform(patch("/api/monitors/{id}", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alertEmails\":[\"x@example.com\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("/recipients")));

        // Another user's monitor is invisible.
        UUID stranger = UUID.randomUUID();
        jdbc.update("insert into users (id, email, email_verified) values (?, 'stranger@example.com', true)", stranger);
        jdbc.update("update monitors set user_id = ? where id = ?", stranger, shop.getId());
        mvc.perform(post("/api/monitors/{id}/recipients", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bob@example.com\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void confirmationEmailsAreRateLimitedPerAddress() throws Exception {
        Monitor blog = monitor("Blog", "https://blog.example.com/");
        Monitor api = monitor("API", "https://api.example.com/");
        UUID onShop = addRecipient(shop, ALICE);
        mvc.perform(post("/api/monitors/{id}/recipients/{rid}/resend", shop.getId(), onShop)).andExpect(status().isOk());
        addRecipient(blog, ALICE);

        mvc.perform(post("/api/monitors/{id}/recipients", api.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + ALICE + "\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.detail").value(containsString("to this address")));
        assertThat(emails.to(ALICE)).hasSize(3);
        // Removing and re-adding doesn't reset the address's limit.
        mvc.perform(delete("/api/monitors/{id}/recipients/{rid}", shop.getId(), onShop)).andExpect(status().isNoContent());
        mvc.perform(post("/api/monitors/{id}/recipients", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + ALICE + "\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void confirmationEmailsAreRateLimitedPerAccount() throws Exception {
        for (int i = 1; i <= 10; i++) {
            UUID id = addRecipient(shop, "r" + i + "@example.com");
            mvc.perform(delete("/api/monitors/{id}/recipients/{rid}", shop.getId(), id)).andExpect(status().isNoContent());
        }
        mvc.perform(post("/api/monitors/{id}/recipients", shop.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"r11@example.com\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.detail").value(containsString("from this account")));
        assertThat(jdbc.queryForObject("select count(*) from alert_recipients", Integer.class)).isZero();
    }
}
