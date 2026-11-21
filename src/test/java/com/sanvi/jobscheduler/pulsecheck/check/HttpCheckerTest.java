package com.sanvi.jobscheduler.pulsecheck.check;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;

/**
 * Real HTTP against a local MockWebServer. Loopback is allowed here through the test-only flag;
 * {@link #loopbackIsBlockedWithoutTheTestFlag()} shows the default.
 */
class HttpCheckerTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private static HttpChecker checker(Duration timeout, UrlSafetyValidator.HostResolver resolver, boolean allowLoopback) {
        var config = new PulseCheckProperties.Checker(timeout, 5, 65_536, "PulseCheck/1.0", allowLoopback);
        return new HttpChecker(new UrlSafetyValidator(resolver, allowLoopback), config);
    }

    private static HttpChecker checker(Duration timeout) {
        return checker(timeout, InetAddress::getAllByName, true);
    }

    private static HttpChecker checker() {
        return checker(Duration.ofSeconds(5));
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    private static MockResponse status(int code) {
        return new MockResponse.Builder().code(code).body("body").build();
    }

    private static MockResponse redirectTo(String location) {
        return new MockResponse.Builder().code(302).setHeader("Location", location).build();
    }

    @Test
    void successfulResponseIsUpAndSendsTheUserAgent() throws Exception {
        server.enqueue(status(200));

        HttpChecker.Outcome outcome = checker().check(url("/health"));

        assertThat(outcome.up()).isTrue();
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(outcome.error()).isNull();
        assertThat(outcome.responseMs()).isGreaterThanOrEqualTo(0);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS).getHeaders().get("User-Agent")).isEqualTo("PulseCheck/1.0");
    }

    @Test
    void any2xxIsUp() {
        server.enqueue(new MockResponse.Builder().code(204).build()); // 204 must not carry a body
        assertThat(checker().check(url("/")).up()).isTrue();
    }

    @Test
    void redirectWithoutLocationCountsAsUp() {
        server.enqueue(status(304));
        HttpChecker.Outcome outcome = checker().check(url("/"));
        assertThat(outcome.up()).isTrue();
        assertThat(outcome.statusCode()).isEqualTo(304);
    }

    @Test
    void serverErrorIsDownWithStatus() {
        server.enqueue(status(500));

        HttpChecker.Outcome outcome = checker().check(url("/"));

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.statusCode()).isEqualTo(500);
        assertThat(outcome.error()).isEqualTo("HTTP 500");
    }

    @Test
    void clientErrorIsDown() {
        server.enqueue(status(404));
        assertThat(checker().check(url("/missing")).error()).isEqualTo("HTTP 404");
    }

    @Test
    void slowServerTimesOut() {
        server.enqueue(new MockResponse.Builder().code(200).headersDelay(3, TimeUnit.SECONDS).build());

        long start = System.nanoTime();
        HttpChecker.Outcome outcome = checker(Duration.ofSeconds(1)).check(url("/slow"));

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.error()).startsWith("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void connectionRefusedIsDown() {
        String closedPortUrl = url("/");
        server.close();

        HttpChecker.Outcome outcome = checker().check(closedPortUrl);

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.error()).isEqualTo("connection refused");
    }

    @Test
    void redirectsAreFollowed() {
        server.enqueue(redirectTo("/step2"));
        server.enqueue(redirectTo(url("/final")));
        server.enqueue(status(200));

        HttpChecker.Outcome outcome = checker().check(url("/start"));

        assertThat(outcome.up()).isTrue();
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void fiveRedirectsAreAllowedButASixthIsNot() {
        for (int i = 0; i < 5; i++) {
            server.enqueue(redirectTo("/hop" + i));
        }
        server.enqueue(status(200));
        assertThat(checker().check(url("/")).up()).isTrue();

        for (int i = 0; i < 6; i++) {
            server.enqueue(redirectTo("/loop" + i));
        }
        HttpChecker.Outcome tooMany = checker().check(url("/"));
        assertThat(tooMany.up()).isFalse();
        assertThat(tooMany.error()).startsWith("too many redirects");
    }

    @Test
    void redirectToAPrivateAddressIsBlockedAndNotFollowed() {
        server.enqueue(redirectTo("http://10.0.0.1/admin"));

        HttpChecker.Outcome outcome = checker().check(url("/"));

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.error()).startsWith("redirect blocked:").contains("10.0.0.1");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void redirectToCloudMetadataIsBlocked() {
        server.enqueue(redirectTo("http://169.254.169.254/latest/meta-data/iam/security-credentials/"));

        HttpChecker.Outcome outcome = checker().check(url("/"));

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.error()).startsWith("redirect blocked:");
    }

    @Test
    void redirectToAHostNameThatResolvesPrivatelyIsBlocked() {
        UrlSafetyValidator.HostResolver resolver = host -> host.equals("internal.example")
            ? new InetAddress[] {InetAddress.getByName("192.168.1.10")}
            : InetAddress.getAllByName(host);
        server.enqueue(redirectTo("http://internal.example/"));

        HttpChecker.Outcome outcome = checker(Duration.ofSeconds(5), resolver, true).check(url("/"));

        assertThat(outcome.error()).startsWith("redirect blocked:").contains("192.168.1.10");
    }

    @Test
    void redirectToANonHttpSchemeIsDown() {
        server.enqueue(redirectTo("file:///etc/passwd"));
        assertThat(checker().check(url("/")).up()).isFalse();
    }

    @Test
    void loopbackIsBlockedWithoutTheTestFlag() {
        server.enqueue(status(200));

        HttpChecker.Outcome outcome = checker(Duration.ofSeconds(5), InetAddress::getAllByName, false).check(url("/"));

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.error()).startsWith("blocked:");
        assertThat(server.getRequestCount()).as("no request may reach the server").isZero();
    }

    @Test
    void connectionIsPinnedToTheValidatedAddressSoDnsRebindingCannotWork() {
        // First lookup answers loopback (allowed in this test), any later lookup would answer a private IP.
        AtomicInteger lookups = new AtomicInteger();
        UrlSafetyValidator.HostResolver rebinding = host -> {
            if (!host.equals("rebind.test")) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(lookups.getAndIncrement() == 0 ? "127.0.0.1" : "10.0.0.1")};
        };
        server.enqueue(status(200));

        HttpChecker.Outcome outcome = checker(Duration.ofSeconds(5), rebinding, true)
            .check("http://rebind.test:" + server.getPort() + "/");

        assertThat(outcome.up()).isTrue();
        assertThat(lookups.get()).as("resolved exactly once; the connection reused the validated address").isEqualTo(1);
    }

    @Test
    void onlyTheFirst64KbOfTheBodyAreRead() {
        // 1 MB body delivered 64 KB at a time with a 3 s pause between chunks: reading it all would take ~48 s.
        Buffer bigBody = new Buffer().write(new byte[1024 * 1024]);
        server.enqueue(new MockResponse.Builder().code(200).body(bigBody)
            .throttleBody(65_536, 3, TimeUnit.SECONDS).build());

        long start = System.nanoTime();
        HttpChecker.Outcome outcome = checker(Duration.ofSeconds(10)).check(url("/big"));

        assertThat(outcome.up()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void unresolvableHostIsDownWithAClearMessage() {
        UrlSafetyValidator.HostResolver none = host -> { throw new UnknownHostException(host); };

        HttpChecker.Outcome outcome = checker(Duration.ofSeconds(5), none, false).check("https://no-such-host.invalid/");

        assertThat(outcome.up()).isFalse();
        assertThat(outcome.error()).isEqualTo("could not resolve host no-such-host.invalid");
    }
}
