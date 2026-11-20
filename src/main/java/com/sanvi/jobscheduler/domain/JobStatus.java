package com.sanvi.jobscheduler.domain;

/**
 * Job lifecycle. Must stay in sync with the {@code ck_jobs_status} CHECK constraint.
 *
 * <pre>
 * SCHEDULED -> QUEUED/RUNNING -> SUCCEEDED
 *                    |       -> QUEUED (retry / recovery) -> ... -> DEAD
 * SCHEDULED/QUEUED/RUNNING -> CANCELLED
 * </pre>
 */
public enum JobStatus {
    /** Waiting for a future {@code runAt} (delayed submission or retry backoff). */
    SCHEDULED,
    /** Ready to run. */
    QUEUED,
    RUNNING,
    SUCCEEDED,
    /** Failed permanently; can be requeued manually. */
    DEAD,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD || this == CANCELLED;
    }
}
