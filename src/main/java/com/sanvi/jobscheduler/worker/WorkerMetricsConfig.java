package com.sanvi.jobscheduler.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Configuration
@Profile("worker")
public class WorkerMetricsConfig {

    @Bean
    Timer jobDurationTimer(MeterRegistry registry) {
        return Timer.builder("job_duration")
            .publishPercentileHistogram()
            .description("Time spent processing jobs")
            .register(registry);
    }
}
