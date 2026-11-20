package com.sanvi.jobscheduler.worker;

/** Thrown by a handler when retrying cannot help (bad payload, permanent rejection): the job goes straight to DEAD. */
public class NonRetryableJobException extends RuntimeException {
    public NonRetryableJobException(String message) {
        super(message);
    }

    public NonRetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
