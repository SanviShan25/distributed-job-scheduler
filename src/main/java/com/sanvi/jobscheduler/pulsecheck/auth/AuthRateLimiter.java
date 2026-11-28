package com.sanvi.jobscheduler.pulsecheck.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

import com.sanvi.jobscheduler.security.RedisRateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis token buckets for the account endpoints, shared by all API instances: login per IP and per
 * email, sign-up per IP. Over the limit: 429 with {@code Retry-After}. Every attempt counts, successful
 * or not. Fails open if Redis is unavailable (the account lockout still applies).
 */
@Component
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimiter {
    private final RedisRateLimiter limiter;
    private final AuthProtectionProperties props;

    public void checkLogin(String clientIp, String normalizedEmail) {
        consume("login:ip:" + clientIp, props.loginPerIpPerMinute(), 60, "Too many login attempts");
        if (normalizedEmail != null && !normalizedEmail.isBlank()) {
            consume("login:email:" + fingerprint(normalizedEmail), props.loginPerEmailPerMinute(), 60,
                "Too many login attempts");
        }
    }

    public void checkSignup(String clientIp) {
        consume("signup:ip:" + clientIp, props.signupPerIpPerHour(), 3600, "Too many sign-ups");
    }

    public void checkDemoLogin(String clientIp) {
        consume("demo:ip:" + clientIp, props.demoLoginsPerIpPerHour(), 3600, "Too many demo logins");
    }

    public void checkResendVerification(java.util.UUID userId) {
        consume("resend:user:" + userId, props.resendVerificationPerUserPerHour(), 3600,
            "Too many verification emails requested");
    }

    public void checkForgotPassword(String clientIp, String normalizedEmail) {
        consume("forgot:ip:" + clientIp, props.forgotPasswordPerIpPerHour(), 3600, "Too many password reset requests");
        if (normalizedEmail != null && !normalizedEmail.isBlank()) {
            consume("forgot:email:" + fingerprint(normalizedEmail), props.forgotPasswordPerEmailPerHour(), 3600,
                "Too many password reset requests");
        }
    }

    private void consume(String bucket, int limit, int perSeconds, String message) {
        if (!props.rateLimitsEnabled()) {
            return;
        }
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.tryConsume("auth:" + bucket, limit, (double) limit / perSeconds);
        } catch (RuntimeException e) {
            log.warn("Auth rate limiter unavailable; allowing request", e);
            return;
        }
        if (decision.allowed()) {
            return;
        }
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
            message + ". Please try again in " + retryAfterSeconds + " seconds.");
        problem.setTitle("Too Many Requests");
        ErrorResponseException error = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
        error.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        throw error;
    }

    /** Emails never appear in Redis key names. */
    private static String fingerprint(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
