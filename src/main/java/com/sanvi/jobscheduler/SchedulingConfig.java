package com.sanvi.jobscheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} background tasks (reaper, sweepers, mover, ...).
 * Integration tests turn this off with {@code scheduler.scheduling-enabled=false}
 * so they can invoke those tasks deterministically.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "scheduler.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
