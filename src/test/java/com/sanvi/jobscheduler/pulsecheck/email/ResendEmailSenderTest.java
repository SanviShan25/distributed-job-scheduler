package com.sanvi.jobscheduler.pulsecheck.email;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resend API calls against a local MockWebServer; the real Resend API is never called. */
class ResendEmailSenderTest {
    private static final String API_KEY = "re_test_secret_key";
    private static final EmailSender.Email EMAIL = new EmailSender.Email("user@example.com", "Hello",
        "plain text part", "<p>html part</p>");

    private MockWebServer server;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private ResendEmailSender sender(Duration timeout) {
        return new ResendEmailSender(new EmailProperties(EmailProperties.Provider.RESEND,
            "PulseCheck <alerts@example.com>",
            new EmailProperties.Resend(API_KEY, server.url("/").toString(), timeout)));
    }

    private ResendEmailSender sender() {
        return sender(Duration.ofSeconds(5));
    }

    private static MockResponse status(int code, String body) {
        return new MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build();
    }

    @Test
    void sendsTheExpectedRequest() throws Exception {
        server.enqueue(status(200, "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}"));

        sender().send(new EmailSender.Email("user@example.com", "Hello", "plain text part", "<p>html part</p>",
            Map.of("List-Unsubscribe", "<https://example.com/u>"), "email:verify:123"));

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/emails");
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer " + API_KEY);
        assertThat(request.getHeaders().get("Content-Type")).startsWith("application/json");
        assertThat(request.getHeaders().get("Idempotency-Key")).isEqualTo("email:verify:123");

        JsonNode body = json.readTree(request.getBody().utf8());
        assertThat(body.path("from").asString()).isEqualTo("PulseCheck <alerts@example.com>");
        assertThat(body.path("to").get(0).asString()).isEqualTo("user@example.com");
        assertThat(body.path("to").size()).isEqualTo(1);
        assertThat(body.path("subject").asString()).isEqualTo("Hello");
        assertThat(body.path("text").asString()).isEqualTo("plain text part");
        assertThat(body.path("html").asString()).isEqualTo("<p>html part</p>");
        assertThat(body.path("headers").path("List-Unsubscribe").asString()).isEqualTo("<https://example.com/u>");
    }

    @Test
    void noIdempotencyHeaderOrHeadersObjectWhenNotNeeded() throws Exception {
        server.enqueue(status(200, "{\"id\":\"x\"}"));

        sender().send(EMAIL);

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getHeaders().get("Idempotency-Key")).isNull();
        assertThat(json.readTree(request.getBody().utf8()).has("headers")).isFalse();
    }

    @ParameterizedTest(name = "HTTP {0} is retryable")
    @ValueSource(ints = {429, 409, 500, 502, 503, 504})
    void rateLimitsAndServerErrorsAreRetryable(int code) {
        server.enqueue(status(code, "{\"statusCode\":" + code + ",\"name\":\"rate_limit_exceeded\",\"message\":\"slow down\"}"));

        assertThatThrownBy(() -> sender().send(EMAIL))
            .isInstanceOfSatisfying(EmailSendException.class, e -> {
                assertThat(e.isRetryable()).isTrue();
                assertThat(e.status()).isEqualTo(code);
            });
    }

    @ParameterizedTest(name = "HTTP {0} is not retryable")
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void validationAndAuthErrorsAreNotRetryable(int code) {
        server.enqueue(status(code, "{\"statusCode\":" + code + ",\"name\":\"validation_error\","
            + "\"message\":\"The gmail.com domain is not verified.\"}"));

        assertThatThrownBy(() -> sender().send(EMAIL))
            .isInstanceOfSatisfying(EmailSendException.class, e -> {
                assertThat(e.isRetryable()).isFalse();
                assertThat(e.status()).isEqualTo(code);
                assertThat(e.getMessage()).contains("validation_error").contains("domain is not verified");
            });
    }

    @Test
    void aTimeoutIsRetryable() {
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build());

        assertThatThrownBy(() -> sender(Duration.ofMillis(300)).send(EMAIL))
            .isInstanceOfSatisfying(EmailSendException.class, e -> {
                assertThat(e.isRetryable()).isTrue();
                assertThat(e.status()).isZero();
            });
    }

    @Test
    void anUnreachableApiIsRetryable() {
        ResendEmailSender sender = sender();
        server.close();

        assertThatThrownBy(() -> sender.send(EMAIL))
            .isInstanceOfSatisfying(EmailSendException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void theApiKeyNeverAppearsInErrors() {
        server.enqueue(status(401, "{\"statusCode\":401,\"name\":\"missing_api_key\",\"message\":\"bad key\"}"));

        assertThatThrownBy(() -> sender().send(EMAIL)).satisfies(e -> assertThat(e.getMessage()).doesNotContain(API_KEY));
    }
}
