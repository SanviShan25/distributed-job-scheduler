package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/** Email normalisation and a pragmatic format check (one @, a dot in the domain, no spaces). */
public final class EmailAddresses {
    public static final int MAX_LENGTH = 254;
    private static final Pattern FORMAT = Pattern.compile(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    private EmailAddresses() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String normalized) {
        return normalized != null && normalized.length() <= MAX_LENGTH && FORMAT.matcher(normalized).matches();
    }
}
