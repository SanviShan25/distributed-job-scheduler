package com.sanvi.jobscheduler.pulsecheck.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One HTTP check of a monitor. High volume, so it uses a database-generated bigint id. */
@Entity @Table(name = "check_results")
@Getter @Setter @NoArgsConstructor
public class CheckResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private UUID monitorId;
    private Instant checkedAt;
    private boolean up;
    /** Null when no HTTP response was received (timeout, DNS failure, blocked address...). */
    private Integer statusCode;
    private Integer responseMs;
    private String error;
}
