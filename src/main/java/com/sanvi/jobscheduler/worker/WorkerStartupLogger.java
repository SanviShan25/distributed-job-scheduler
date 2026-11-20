package com.sanvi.jobscheduler.worker;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Component
@Profile("worker")
@Slf4j
public class WorkerStartupLogger implements EnvironmentAware {
    @Value("${worker.id:}")
    private String workerId;

    @Value("${server.port:8080}")
    private String serverPort;

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateWorkerId() {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("Worker configuration error: worker.id must not be blank");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logWorkerStartup() {
        log.info("Worker started: id={} port={} profiles={}", workerId, serverPort,
            Arrays.toString(environment.getActiveProfiles()));
    }
}
