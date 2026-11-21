package com.sanvi.jobscheduler.pulsecheck;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pulsecheck.*} settings.
 *
 * @param devUserEmail account seeded by {@link DevUserSeeder} under the {@code dev} profile
 */
@ConfigurationProperties(prefix = "pulsecheck")
public record PulseCheckProperties(
    @DefaultValue("dev@pulsecheck.local") String devUserEmail) {
}
