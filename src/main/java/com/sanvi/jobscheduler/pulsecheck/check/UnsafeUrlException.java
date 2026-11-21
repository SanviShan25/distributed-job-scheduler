package com.sanvi.jobscheduler.pulsecheck.check;

/**
 * The URL may not be fetched: bad syntax, a forbidden scheme, credentials, or a host that resolves to
 * a private/reserved address. {@link #isDnsFailure()} distinguishes "host does not resolve" (the site
 * may simply be down) from "host is not allowed" (a policy rejection).
 */
public class UnsafeUrlException extends RuntimeException {
    private final boolean dnsFailure;

    public UnsafeUrlException(String message) {
        this(message, false);
    }

    public UnsafeUrlException(String message, boolean dnsFailure) {
        super(message);
        this.dnsFailure = dnsFailure;
    }

    public boolean isDnsFailure() {
        return dnsFailure;
    }
}
