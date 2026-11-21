package com.sanvi.jobscheduler.pulsecheck.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A PulseCheck account. Named AppUser to avoid clashing with security "User" types. */
@Entity @Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id private UUID id;
    private String email;
    /** Null until login exists. */
    private String passwordHash;
    private Instant createdAt;
}
