package com.sanvi.jobscheduler.pulsecheck.api;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;
import com.sanvi.jobscheduler.pulsecheck.repo.AppUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Who is calling the PulseCheck API. TEMPORARY: until login exists, every request authenticated with the
 * scheduler's API key acts as the dev user ({@code pulsecheck.dev-user-email}, seeded under the {@code dev}
 * profile). This is the single place to replace when real authentication arrives.
 */
@Component
@Profile("api")
@RequiredArgsConstructor
public class CurrentUser {
    private final AppUserRepository users;
    private final PulseCheckProperties props;

    public UUID id() {
        return users.findByEmail(props.devUserEmail())
            .orElseThrow(() -> {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "No PulseCheck user is set up. Start the API with the 'dev' profile to create the dev user.");
                problem.setTitle("Service Unavailable");
                return new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem, null);
            })
            .getId();
    }
}
