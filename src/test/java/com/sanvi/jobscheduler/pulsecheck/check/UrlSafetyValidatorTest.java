package com.sanvi.jobscheduler.pulsecheck.check;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlSafetyValidatorTest {

    private final UrlSafetyValidator strict = new UrlSafetyValidator(InetAddress::getAllByName, false);

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal); // literals only: no DNS lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A resolver that maps every host to the given literal addresses. */
    private static UrlSafetyValidator resolvingTo(String... literals) {
        return new UrlSafetyValidator(host -> {
            InetAddress[] result = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                result[i] = ip(literals[i]);
            }
            return result;
        }, false);
    }

    @ParameterizedTest(name = "blocked: {0}")
    @ValueSource(strings = {
        // 0.0.0.0/8
        "0.0.0.0", "0.255.255.255",
        // 10.0.0.0/8
        "10.0.0.1", "10.255.255.255",
        // 172.16.0.0/12
        "172.16.0.1", "172.31.255.255",
        // 192.168.0.0/16
        "192.168.0.1", "192.168.255.255",
        // 127.0.0.0/8
        "127.0.0.1", "127.255.255.254",
        // 169.254.0.0/16, incl. cloud metadata
        "169.254.0.1", "169.254.169.254",
        // 100.64.0.0/10 (CGNAT, incl. Alibaba metadata)
        "100.64.0.1", "100.100.100.200", "100.127.255.255",
        // multicast / reserved / broadcast
        "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
        // IPv6 loopback, unspecified
        "::1", "::",
        // fc00::/7 unique local, incl. AWS IPv6 metadata
        "fc00::1", "fd00:ec2::254", "fdff:ffff::1",
        // fe80::/10 link-local
        "fe80::1", "febf:ffff::1",
        // IPv6 multicast
        "ff02::1",
        // IPv4 hidden inside IPv6: mapped, NAT64, 6to4, compatible
        "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1", "::7f00:1"})
    void privateAndReservedAddressesAreBlocked(String literal) {
        assertThat(strict.isBlocked(ip(literal))).isTrue();
    }

    @ParameterizedTest(name = "allowed: {0}")
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "93.184.215.14",
        // just outside the blocked ranges
        "9.255.255.255", "11.0.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0",
        "100.63.255.255", "100.128.0.0", "169.253.255.255", "169.255.0.0", "126.255.255.255", "128.0.0.0",
        "223.255.255.255",
        // public IPv6
        "2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:8.8.8.8"})
    void publicAddressesAreAllowed(String literal) {
        assertThat(strict.isBlocked(ip(literal))).isFalse();
    }

    @ParameterizedTest(name = "rejected: {0}")
    @ValueSource(strings = {
        "ftp://example.com/file", "file:///etc/passwd", "javascript:alert(1)", "gopher://example.com",
        "example.com", "//example.com", "http://", "", "   ",
        "http://user:secret@example.com/", "https://user@example.com/"})
    void onlyCredentialFreeHttpUrlsAreAccepted(String url) {
        assertThatThrownBy(() -> resolvingTo("93.184.215.14").validate(url)).isInstanceOf(UnsafeUrlException.class);
    }

    @Test
    void urlsLongerThan2048CharactersAreRejected() {
        String base = "https://example.com/";
        String ok = base + "a".repeat(UrlSafetyValidator.MAX_URL_LENGTH - base.length());
        String tooLong = ok + "a";
        UrlSafetyValidator validator = resolvingTo("93.184.215.14");

        assertThat(validator.validate(ok).url().toString()).hasSize(UrlSafetyValidator.MAX_URL_LENGTH);
        assertThatThrownBy(() -> validator.validate(tooLong)).hasMessageContaining("2048");
    }

    @ParameterizedTest(name = "literal host rejected: {0}")
    @ValueSource(strings = {
        "http://169.254.169.254/latest/meta-data/", "http://127.0.0.1:8080/", "http://localhost/",
        "http://[::1]/", "http://[fd00:ec2::254]/", "http://10.0.0.1/", "http://2130706433/", "http://0.0.0.0/"})
    void internalHostsWrittenDirectlyInTheUrlAreRejected(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(UnsafeUrlException.class);
    }

    @Test
    void hostResolvingToAPrivateAddressIsRejected() {
        assertThatThrownBy(() -> resolvingTo("10.1.2.3").validate("https://intranet.example.com/"))
            .isInstanceOf(UnsafeUrlException.class)
            .hasMessageContaining("10.1.2.3")
            .satisfies(e -> assertThat(((UnsafeUrlException) e).isDnsFailure()).isFalse());
    }

    @Test
    void hostIsRejectedIfAnyOfItsAddressesIsPrivate() {
        assertThatThrownBy(() -> resolvingTo("93.184.215.14", "169.254.169.254").validate("https://mixed.example.com/"))
            .hasMessageContaining("169.254.169.254");
    }

    @Test
    void publicHostReturnsTheAddressesToPinTo() {
        UrlSafetyValidator.Target target = resolvingTo("93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c")
            .validate("https://example.com/path?q=1");

        assertThat(target.url().host()).isEqualTo("example.com");
        assertThat(target.addresses()).extracting(InetAddress::getHostAddress)
            .containsExactly("93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c");
    }

    @Test
    void unresolvableHostIsReportedAsDnsFailure() {
        UrlSafetyValidator validator = new UrlSafetyValidator(host -> { throw new UnknownHostException(host); }, false);
        assertThatThrownBy(() -> validator.validate("https://no-such-host.invalid/"))
            .isInstanceOf(UnsafeUrlException.class)
            .satisfies(e -> assertThat(((UnsafeUrlException) e).isDnsFailure()).isTrue());
    }

    @Test
    void loopbackFlagAllowsOnlyLoopback() {
        UrlSafetyValidator testOnly = new UrlSafetyValidator(InetAddress::getAllByName, true);

        assertThat(testOnly.isBlocked(ip("127.0.0.1"))).isFalse();
        assertThat(testOnly.isBlocked(ip("::1"))).isFalse();
        assertThat(testOnly.isBlocked(ip("10.0.0.1"))).isTrue();
        assertThat(testOnly.isBlocked(ip("169.254.169.254"))).isTrue();
        assertThat(testOnly.isBlocked(ip("192.168.1.1"))).isTrue();
    }

    @Test
    void loopbackFlagIsOffByDefault() {
        assertThat(com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties.Checker.defaults().allowLoopback()).isFalse();
    }
}
