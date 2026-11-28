package com.sanvi.jobscheduler.pulsecheck.domain;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
    /** Stored trimmed and lower-cased; unique case-insensitively. */
    private String email;
    /** Argon2id hash with a {@code {argon2}} prefix. Never serialized. Null only for legacy seeded users. */
    @JsonIgnore private String passwordHash;
    private Instant createdAt;
    private boolean emailVerified;
    @JsonIgnore private int failedLoginCount;
    @JsonIgnore private Instant lockedUntil;
    /** The shared demo account (demo mode): no password, no emails, restricted. At most one exists. */
    private boolean demo;
}
