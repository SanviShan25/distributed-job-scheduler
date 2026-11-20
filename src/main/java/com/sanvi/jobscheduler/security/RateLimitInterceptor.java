package com.sanvi.jobscheduler.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies the per-API-key token bucket to {@code POST /jobs}. Fails open: if Redis is unavailable the
 * request proceeds (submission would fail on the enqueue anyway, and a limiter outage should not
 * become an API outage).
 */
@Configuration(proxyBeanMethods = false)
@Profile("api")
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private final RedisRateLimiter limiter;
    private final ApiSecurityProperties props;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/jobs");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ApiSecurityProperties.RateLimit cfg = props.rateLimit();
        if (!cfg.enabled() || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String bucket = "submit:" + (auth == null ? "anonymous" : auth.getName());
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.tryConsume(bucket, cfg.capacity(), cfg.refillPerSecond());
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable; allowing request", e);
            return true;
        }
        response.setHeader("X-RateLimit-Limit", String.valueOf(cfg.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (decision.allowed()) {
            return true;
        }
        long retryAfterSeconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
            "Rate limit exceeded for job submissions; retry after " + retryAfterSeconds + "s");
        problem.setTitle("Too Many Requests");
        ErrorResponseException error = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS, problem, null);
        error.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        throw error;
    }
}
