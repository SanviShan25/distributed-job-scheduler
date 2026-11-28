package com.sanvi.jobscheduler.pulsecheck.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sanvi.jobscheduler.pulsecheck.auth.SignupProperties;
import com.sanvi.jobscheduler.pulsecheck.demo.DemoProperties;

import lombok.RequiredArgsConstructor;

/**
 * What the web app needs to know before anyone logs in. Public. Only switches, never the sign-up allowlist itself.
 */
@RestController
@RequestMapping("/api/config")
@Profile("api")
@RequiredArgsConstructor
public class ConfigController {
    private final DemoProperties demo;
    private final SignupProperties signup;

    /**
     * @param demoEnabled "Try the demo" is available ({@code POST /api/auth/demo})
     * @param signupOpen  anyone may sign up; false when sign-ups are limited to certain addresses
     */
    public record ConfigView(boolean demoEnabled, boolean signupOpen) {}

    @GetMapping
    public ConfigView config() {
        return new ConfigView(demo.enabled(), signup.open());
    }
}
