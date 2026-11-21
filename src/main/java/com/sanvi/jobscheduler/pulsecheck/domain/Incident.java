package com.sanvi.jobscheduler.pulsecheck.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A period during which a monitor was DOWN. At most one open (unresolved) incident per monitor. */
@Entity @Table(name = "incidents")
@Getter @Setter @NoArgsConstructor
public class Incident {
    @Id private UUID id;
    private UUID monitorId;
    private Instant startedAt;
    private Instant resolvedAt;
    private String cause;
}
