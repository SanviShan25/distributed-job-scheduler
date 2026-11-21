package com.sanvi.jobscheduler.pulsecheck.check;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Performs one uptime check of a URL with SSRF protection.
 *
 * <ul>
 *   <li>Every hop (the URL and each redirect target) is validated by {@link UrlSafetyValidator}, and the
 *       connection is pinned to the addresses that passed validation (no second DNS lookup, so DNS
 *       rebinding cannot reach an internal address). TLS still verifies the certificate for the host name.</li>
 *   <li>At most {@code maxRedirects} redirects; the whole check shares one {@code timeout} budget.</li>
 *   <li>At most {@code maxBodyBytes} of the body are read; no proxy is used.</li>
 *   <li>2xx/3xx = up. Anything else, a timeout, a connection error or a blocked address = down,
 *       with a short human-readable error. This method never throws for website problems.</li>
 * </ul>
 */
@Component
public class HttpChecker {
    private static final int MAX_ERROR_LENGTH = 500;

    /** Result of one check. {@code statusCode} is null when no HTTP response was received. */
    public record Outcome(boolean up, Integer statusCode, int responseMs, String error) {
        static Outcome up(int status, int ms) {
            return new Outcome(true, status, ms, null);
        }

        static Outcome down(Integer status, int ms, String error) {
            String message = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
            return new Outcome(false, status, ms, message);
        }
    }

    private final UrlSafetyValidator validator;
    private final PulseCheckProperties.Checker config;
    private final OkHttpClient baseClient;

    @Autowired
    public HttpChecker(UrlSafetyValidator validator, PulseCheckProperties props) {
        this(validator, props.checker());
    }

    public HttpChecker(UrlSafetyValidator validator, PulseCheckProperties.Checker config) {
        this.validator = validator;
        this.config = config;
        this.baseClient = new OkHttpClient.Builder()
            .followRedirects(false)        // redirects are followed manually so each hop is re-validated
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)         // a proxy would resolve the host itself, bypassing the pinning
            .connectTimeout(config.timeout())
            .readTimeout(config.timeout())
            .writeTimeout(config.timeout())
            .build();
    }

    public Outcome check(String url) {
        long start = System.nanoTime();
        long deadline = start + config.timeout().toNanos();
        HttpUrl current;
        try {
            current = validator.parse(url);
        } catch (UnsafeUrlException e) {
            return Outcome.down(null, 0, "blocked: " + e.getMessage());
        }

        for (int hop = 0; ; hop++) {
            UrlSafetyValidator.Target target;
            try {
                target = validator.validate(current);
            } catch (UnsafeUrlException e) {
                String prefix = e.isDnsFailure() ? "" : (hop == 0 ? "blocked: " : "redirect blocked: ");
                return Outcome.down(null, elapsedMs(start), prefix + e.getMessage());
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Outcome.down(null, elapsedMs(start), timeoutMessage());
            }
            OkHttpClient client = baseClient.newBuilder()
                .dns(pinnedTo(target))
                .callTimeout(Duration.ofNanos(remaining))
                .build();
            Request request = new Request.Builder()
                .url(target.url())
                .header("User-Agent", config.userAgent())
                .header("Accept", "*/*")
                .get()
                .build();

            int status;
            String location;
            try (Response response = client.newCall(request).execute()) {
                status = response.code();
                location = response.header("Location");
                drainLimited(response.body());
            } catch (IOException e) {
                return Outcome.down(null, elapsedMs(start), describe(e));
            }

            if (status >= 300 && status < 400 && location != null) {
                HttpUrl next = current.resolve(location);
                if (next == null) {
                    return Outcome.down(status, elapsedMs(start), "redirect to an invalid or non-HTTP location");
                }
                if (hop >= config.maxRedirects()) {
                    return Outcome.down(status, elapsedMs(start), "too many redirects (more than " + config.maxRedirects() + ")");
                }
                current = next;
                continue;
            }
            int ms = elapsedMs(start);
            return status >= 200 && status < 400 ? Outcome.up(status, ms) : Outcome.down(status, ms, "HTTP " + status);
        }
    }

    /** Dns that only answers for the validated host, with exactly the validated addresses. */
    private static Dns pinnedTo(UrlSafetyValidator.Target target) {
        String host = target.url().host();
        List<InetAddress> addresses = target.addresses();
        return hostname -> {
            if (!hostname.equalsIgnoreCase(host)) {
                throw new UnknownHostException("unexpected host " + hostname);
            }
            return addresses;
        };
    }

    /** Reads at most maxBodyBytes so a large or endless body cannot hold the check open. */
    private void drainLimited(ResponseBody body) throws IOException {
        if (body == null) {
            return;
        }
        BufferedSource source = body.source();
        Buffer sink = new Buffer();
        long total = 0;
        while (total < config.maxBodyBytes()) {
            long read = source.read(sink, Math.min(8192, config.maxBodyBytes() - total));
            if (read == -1) {
                break;
            }
            total += read;
            sink.clear();
        }
    }

    private String describe(IOException e) {
        if (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) {
            return timeoutMessage();
        }
        if (e instanceof UnknownHostException) {
            return "could not resolve host";
        }
        if (e instanceof ConnectException) {
            return "connection refused";
        }
        if (e instanceof NoRouteToHostException) {
            return "host unreachable";
        }
        if (e instanceof SSLException) {
            return "TLS error: " + messageOf(e);
        }
        return "connection error: " + messageOf(e);
    }

    private String timeoutMessage() {
        return "timed out after " + config.timeout().toSeconds() + "s";
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static int elapsedMs(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
}
