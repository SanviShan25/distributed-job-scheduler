package com.sanvi.jobscheduler.pulsecheck.domain;

/** Must stay in sync with the {@code ck_monitors_status} CHECK constraint. */
public enum MonitorStatus {
    /** Last check succeeded. */
    UP,
    /** At least two consecutive checks failed. */
    DOWN,
    /** Not checked yet, or resumed and not re-checked. */
    UNKNOWN,
    /** Checks disabled by the user. */
    PAUSED
}
