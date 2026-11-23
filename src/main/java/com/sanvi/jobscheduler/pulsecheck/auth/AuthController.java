package com.sanvi.jobscheduler.pulsecheck.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/** PulseCheck account endpoints. */
@RestController
@RequestMapping("/api/auth")
@Profile("api")
@RequiredArgsConstructor
public class AuthController {
    private final AccountService accounts;

    public record SignupRequest(@NotNull String email, @NotNull String password) {}

    /** The new account. Deliberately the same for an email that is already registered. */
    public record SignupResponse(String email, boolean emailVerified, String message) {}

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
        AccountService.SignupResult result = accounts.signup(request.email(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(result.email(),
            result.emailVerified(), "Account created. Check your inbox to verify your email address."));
    }
}
