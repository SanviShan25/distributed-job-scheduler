package com.sanvi.jobscheduler.pulsecheck.email;

/**
 * An email could not be sent. Retryable: rate limiting (429), provider outages (5xx), timeouts and
 * network errors. Not retryable: the provider rejected the email itself (other 4xx: invalid address,
 * unverified sender domain, bad API key), so sending it again would fail the same way.
 */
public class EmailSendException extends RuntimeException {
    private final boolean retryable;
    private final int status;

    public EmailSendException(String message, boolean retryable, int status, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** HTTP status from the provider, or 0 if no response was received. */
    public int status() {
        return status;
    }
}
