package com.sanvi.jobscheduler.pulsecheck.auth;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/**
 * The logged-in PulseCheck user, stored in the (Redis) session. {@link #getName()} is the user id, which
 * Spring Session indexes so all of a user's sessions can be found (and ended) at once.
 */
public record SessionUser(UUID id, String email) implements Principal, Serializable {
    @Override
    public String getName() {
        return id.toString();
    }
}
