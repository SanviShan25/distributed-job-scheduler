package com.sanvi.jobscheduler.pulsecheck.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A website to check periodically. */
@Entity @Table(name = "monitors")
@Getter @Setter @NoArgsConstructor
public class Monitor {
    @Id private UUID id;
    @JsonIgnore private UUID userId;
    private String name;
    private String url;
    private int intervalSeconds;
    private boolean enabled;
    /** Comma-separated; used once alerting exists. */
    private String alertEmails;
    @Enumerated(EnumType.STRING) private MonitorStatus currentStatus;
    private int consecutiveFailures;
    private Instant lastCheckedAt;
    /** The recurring job_schedules row that runs HTTP_CHECK for this monitor. */
    @JsonIgnore private UUID scheduleId;
    private Instant createdAt;
    private Instant updatedAt;
}
