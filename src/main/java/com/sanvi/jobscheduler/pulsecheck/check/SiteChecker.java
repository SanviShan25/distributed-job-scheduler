package com.sanvi.jobscheduler.pulsecheck.check;

/** Checks one URL. Implementations must report website problems as a down outcome, never by throwing. */
@FunctionalInterface
public interface SiteChecker {
    HttpChecker.Outcome check(String url);
}
