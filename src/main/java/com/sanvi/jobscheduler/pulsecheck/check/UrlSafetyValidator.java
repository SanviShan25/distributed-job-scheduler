package com.sanvi.jobscheduler.pulsecheck.check;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;

/**
 * Decides whether a URL is safe for the server to fetch (SSRF protection).
 *
 * <p>A URL is allowed only if it is http(s), at most 2,048 characters, carries no credentials, and
 * <em>every</em> address its host resolves to is a public unicast address. Callers must connect to the
 * addresses returned in {@link Target} (never re-resolve the host), so a DNS answer that changes between
 * validation and connection (DNS rebinding) cannot redirect the request to an internal address.
 */
@Component
@Slf4j
public class UrlSafetyValidator {
    public static final int MAX_URL_LENGTH = 2048;

    /** Resolves a host name; swappable in tests. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** A validated URL together with the addresses that may be used to reach it. */
    public record Target(HttpUrl url, List<InetAddress> addresses) {}

    private static final List<Cidr> BLOCKED_V4 = List.of(
        Cidr.of("0.0.0.0/8"),        // "this network"
        Cidr.of("10.0.0.0/8"),       // private
        Cidr.of("100.64.0.0/10"),    // carrier-grade NAT (also Alibaba metadata 100.100.100.200)
        Cidr.of("127.0.0.0/8"),      // loopback
        Cidr.of("169.254.0.0/16"),   // link-local, incl. cloud metadata 169.254.169.254
        Cidr.of("172.16.0.0/12"),    // private
        Cidr.of("192.0.0.0/24"),     // IETF protocol assignments
        Cidr.of("192.168.0.0/16"),   // private
        Cidr.of("198.18.0.0/15"),    // benchmarking
        Cidr.of("224.0.0.0/4"),      // multicast
        Cidr.of("240.0.0.0/4"));     // reserved, incl. broadcast 255.255.255.255
    private static final List<Cidr> BLOCKED_V6 = List.of(
        Cidr.of("::/128"),           // unspecified
        Cidr.of("::1/128"),          // loopback
        Cidr.of("fc00::/7"),         // unique local (incl. AWS IPv6 metadata fd00:ec2::254)
        Cidr.of("fe80::/10"),        // link-local
        Cidr.of("fec0::/10"),        // deprecated site-local
        Cidr.of("ff00::/8"),         // multicast
        Cidr.of("2001::/32"));       // Teredo (embeds an obfuscated IPv4 address)
    private static final Cidr LOOPBACK_V4 = Cidr.of("127.0.0.0/8");
    private static final Cidr LOOPBACK_V6 = Cidr.of("::1/128");
    // IPv6 forms that embed an IPv4 address, which is checked against the IPv4 rules.
    private static final Cidr IPV4_MAPPED = Cidr.of("::ffff:0:0/96");
    private static final Cidr IPV4_COMPATIBLE = Cidr.of("::/96");
    private static final Cidr NAT64 = Cidr.of("64:ff9b::/96");
    private static final Cidr SIX_TO_FOUR = Cidr.of("2002::/16");

    private final HostResolver resolver;
    private final boolean allowLoopback;

    @Autowired
    public UrlSafetyValidator(PulseCheckProperties props) {
        this(InetAddress::getAllByName, props.checker().allowLoopback());
        if (allowLoopback) {
            log.warn("pulsecheck.checker.allow-loopback is ON: checks may reach 127.0.0.0/8 and ::1. Test use only.");
        }
    }

    public UrlSafetyValidator(HostResolver resolver, boolean allowLoopback) {
        this.resolver = resolver;
        this.allowLoopback = allowLoopback;
    }

    /** Syntax-only checks (no DNS). Returns the parsed URL. */
    public HttpUrl parse(String url) {
        if (url == null || url.isBlank()) {
            throw new UnsafeUrlException("URL is required");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new UnsafeUrlException("URL is longer than " + MAX_URL_LENGTH + " characters");
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new UnsafeUrlException("only http and https URLs are allowed");
        }
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) {
            throw new UnsafeUrlException("not a valid URL");
        }
        if (!parsed.username().isEmpty() || !parsed.password().isEmpty()) {
            throw new UnsafeUrlException("URLs with a username or password are not allowed");
        }
        return parsed;
    }

    /** Full validation: syntax plus DNS. Every resolved address must be allowed. */
    public Target validate(String url) {
        return validate(parse(url));
    }

    public Target validate(HttpUrl url) {
        if (url.toString().length() > MAX_URL_LENGTH) {
            throw new UnsafeUrlException("URL is longer than " + MAX_URL_LENGTH + " characters");
        }
        if (!url.username().isEmpty() || !url.password().isEmpty()) {
            throw new UnsafeUrlException("URLs with a username or password are not allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(url.host());
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("could not resolve host " + url.host(), true);
        }
        if (addresses == null || addresses.length == 0) {
            throw new UnsafeUrlException("could not resolve host " + url.host(), true);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new UnsafeUrlException(url.host() + " resolves to a private or reserved address ("
                    + address.getHostAddress() + ")");
            }
        }
        return new Target(url, List.of(addresses));
    }

    public boolean isBlocked(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (allowLoopback && (LOOPBACK_V4.contains(bytes) || LOOPBACK_V6.contains(bytes))) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return BLOCKED_V4.stream().anyMatch(cidr -> cidr.contains(bytes));
        }
        if (address instanceof Inet6Address) {
            byte[] embedded = embeddedIpv4(bytes);
            if (embedded != null) {
                return isBlocked(toInet(embedded));
            }
            return BLOCKED_V6.stream().anyMatch(cidr -> cidr.contains(bytes));
        }
        return true;
    }

    /** The IPv4 address embedded in IPv4-mapped/-compatible, NAT64 or 6to4 addresses, if any. */
    private static byte[] embeddedIpv4(byte[] v6) {
        if (IPV4_MAPPED.contains(v6) || NAT64.contains(v6)
                || (IPV4_COMPATIBLE.contains(v6) && !Cidr.of("::/127").contains(v6))) {
            return Arrays.copyOfRange(v6, 12, 16);
        }
        if (SIX_TO_FOUR.contains(v6)) {
            return Arrays.copyOfRange(v6, 2, 6);
        }
        return null;
    }

    private static InetAddress toInet(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An IP network in CIDR notation, matched byte-wise. */
    record Cidr(byte[] network, int prefixLength) {
        static Cidr of(String cidr) {
            String[] parts = cidr.split("/");
            return new Cidr(toInet(parseLiteral(parts[0])).getAddress(), Integer.parseInt(parts[1]));
        }

        private static byte[] parseLiteral(String literal) {
            try {
                // Literal IPs only: getByName does not do a DNS lookup for these.
                return InetAddress.getByName(literal).getAddress();
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(literal, e);
            }
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
