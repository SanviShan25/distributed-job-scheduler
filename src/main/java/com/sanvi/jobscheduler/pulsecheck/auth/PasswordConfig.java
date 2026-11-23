package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id password hashing, wrapped in a {@link DelegatingPasswordEncoder} so stored hashes carry an
 * algorithm prefix ({@code {argon2}…}) and the algorithm or its parameters can be upgraded later without
 * invalidating existing hashes. BCrypt was not used because it only looks at the first 72 bytes, and
 * passwords may be up to 128 characters.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {
    public static final String DEFAULT_ID = "argon2";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(DEFAULT_ID,
            Map.of(DEFAULT_ID, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
