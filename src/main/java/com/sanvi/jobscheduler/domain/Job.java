package com.sanvi.jobscheduler.domain;

import java.time.Instant;
import java.util.UUID;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity @Table(name = "jobs")
@Getter @Setter @NoArgsConstructor
public class Job {
    @Id private UUID id;
    private String type;
    private String payload;
    private int priority;
    @Enumerated(EnumType.STRING) private JobStatus status;
    private int attempts;
    private int maxAttempts;
    private String workerId;
    private String lastError;
    private Instant runAt;
    private Instant createdAt;
    private Instant updatedAt;
    /** Fencing token issued on claim; every completion update must present it. Internal only. */
    @JsonIgnore private UUID leaseToken;
    private Instant leaseExpiresAt;
    private int timeoutSeconds;
    private String idempotencyKey;
    private UUID cronScheduleId;
    private Instant completedAt;
    private boolean cancelRequested;

    /** Job type whose payload can carry a one-time token in a top-level "secret" field. */
    private static final String SEND_EMAIL = "SEND_EMAIL";
    private static final Pattern SECRET_FIELD = Pattern.compile("(\"secret\"\\s*:\\s*)\"[^\"]*\"");

    /** The stored payload, for workers. Not serialized as-is: see {@link #getPayloadForApi()}. */
    @JsonIgnore
    public String getPayload() {
        return payload;
    }

    /**
     * The payload as shown by the admin jobs API: a pending SEND_EMAIL job's one-time token is masked
     * (finished jobs have it removed in the database already).
     */
    @JsonProperty("payload")
    public String getPayloadForApi() {
        if (!SEND_EMAIL.equals(type) || payload == null) {
            return payload;
        }
        return SECRET_FIELD.matcher(payload).replaceAll("$1\"[redacted]\"");
    }
}
