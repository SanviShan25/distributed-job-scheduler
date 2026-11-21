package com.sanvi.jobscheduler.pulsecheck;

import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates the development user ({@code pulsecheck.dev-user-email}) at startup. Active only under the
 * {@code dev} profile, so production databases never get an account without a password. Safe to run on
 * every start and from several instances at once ({@code ON CONFLICT DO NOTHING}).
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevUserSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PulseCheckProperties props;

    @Override
    public void run(ApplicationArguments args) {
        int inserted = jdbc.update(
            "insert into users (id, email, created_at) values (?, ?, now()) on conflict (email) do nothing",
            UUID.randomUUID(), props.devUserEmail());
        if (inserted == 1) {
            log.info("Seeded PulseCheck dev user {}", props.devUserEmail());
        }
    }
}
