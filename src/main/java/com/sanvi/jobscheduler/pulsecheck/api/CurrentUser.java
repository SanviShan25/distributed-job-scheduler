package com.sanvi.jobscheduler.pulsecheck.api;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.pulsecheck.auth.AccountService;
import com.sanvi.jobscheduler.pulsecheck.auth.SessionUser;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * The logged-in PulseCheck user for the current request. Every monitor, check and incident query is
 * scoped to this id. A session whose account has since been deleted, or whose demo sandbox has expired (cleanup may
 * not have run yet), is treated as logged out.
 */
@Component
@Profile("api")
@RequiredArgsConstructor
public class CurrentUser {
    private final AppUserRepository users;

    public UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionUser user)) {
            throw AccountService.invalidCredentials();
        }
        if (!users.isActive(user.id())) {
            throw AccountService.invalidCredentials();
        }
        return user.id();
    }
}
