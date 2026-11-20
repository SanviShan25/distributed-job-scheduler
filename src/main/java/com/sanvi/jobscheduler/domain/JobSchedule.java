package com.sanvi.jobscheduler.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A recurring job definition; each fire creates one row in {@code jobs}. */
@Entity @Table(name = "job_schedules")
@Getter @Setter @NoArgsConstructor
public class JobSchedule {
    @Id private UUID id;
    private String name;
    @Column(name = "cron_expression") private String cron;
    private String timezone;
    private String type;
    private String payload;
    private int priority;
    private int maxAttempts;
    private int timeoutSeconds;
    private boolean enabled;
    private Instant nextRunAt;
    private Instant lastRunAt;
    private Instant createdAt;
    private Instant updatedAt;
}
