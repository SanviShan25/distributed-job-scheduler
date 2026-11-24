package com.sanvi.jobscheduler.pulsecheck.email;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends email through Resend's HTTP API ({@code POST /emails}).
 *
 * <ul>
 *   <li>2xx: sent; Resend's message id is logged (never the content, which may hold one-time links).</li>
 *   <li>429, 5xx, 409 (a concurrent request with the same idempotency key), timeouts and network errors:
 *       {@link EmailSendException} with {@code retryable = true}.</li>
 *   <li>Any other 4xx (validation error, unverified sender domain, bad API key): {@code retryable = false},
 *       because sending the same request again would fail the same way.</li>
 * </ul>
 * If the email has an idempotency key it is sent as Resend's {@code Idempotency-Key} header, so a retry
 * after an ambiguous failure (for example a timeout after Resend accepted the email) is not delivered twice.
 */
@Slf4j
public class ResendEmailSender implements EmailSender {
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int MAX_ERROR_LENGTH = 300;

    private final OkHttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String endpoint;
    private final String apiKey;
    private final String from;

    public ResendEmailSender(EmailProperties props) {
        EmailProperties.Resend resend = props.resend();
        String base = resend.baseUrl().endsWith("/")
            ? resend.baseUrl().substring(0, resend.baseUrl().length() - 1) : resend.baseUrl();
        this.endpoint = base + "/emails";
        this.apiKey = resend.apiKey();
        this.from = props.from();
        this.client = new OkHttpClient.Builder()
            .callTimeout(resend.timeout())
            .connectTimeout(resend.timeout())
            .readTimeout(resend.timeout())
            .retryOnConnectionFailure(false) // retries are the job scheduler's decision, with backoff
            .build();
    }

    @Override
    public void send(Email email) {
        Request.Builder request = new Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .header("User-Agent", "PulseCheck/1.0")
            .post(RequestBody.create(json.writeValueAsString(body(email)), JSON));
        if (email.idempotencyKey() != null && !email.idempotencyKey().isBlank()) {
            request.header("Idempotency-Key", email.idempotencyKey());
        }

        try (Response response = client.newCall(request.build()).execute()) {
            String responseBody = readBody(response.body());
            int status = response.code();
            if (response.isSuccessful()) {
                log.info("Email \"{}\" accepted by Resend (id {})", email.subject(), messageId(responseBody));
                return;
            }
            boolean retryable = status == 429 || status == 409 || status >= 500;
            String reason = "Resend rejected the email: HTTP " + status + " " + errorMessage(responseBody);
            if (!retryable) {
                log.error("{} (not retryable; check the API key, the sender domain and the recipient)", reason);
            }
            throw new EmailSendException(reason, retryable, status, null);
        } catch (IOException e) {
            // Timeouts, DNS failures, refused connections: Resend may be down or slow; try again later.
            throw new EmailSendException("Could not reach Resend: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " " + e.getMessage()), true, 0, e);
        }
    }

    /** The JSON body of {@code POST /emails}. */
    Map<String, Object> body(Email email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(email.to()));
        body.put("subject", email.subject());
        body.put("html", email.html());
        body.put("text", email.text());
        if (!email.headers().isEmpty()) {
            body.put("headers", email.headers());
        }
        return body;
    }

    private static String readBody(ResponseBody body) throws IOException {
        return body == null ? "" : body.string();
    }

    private String messageId(String responseBody) {
        try {
            JsonNode node = json.readTree(responseBody);
            return node.path("id").asString("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Resend errors look like {"statusCode":422,"name":"validation_error","message":"..."}. */
    private String errorMessage(String responseBody) {
        String message;
        try {
            JsonNode node = json.readTree(responseBody);
            message = node.path("name").asString("") + ": " + node.path("message").asString(responseBody);
        } catch (RuntimeException e) {
            message = responseBody;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
