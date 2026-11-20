package com.sanvi.jobscheduler.repo;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.sanvi.jobscheduler.domain.Job;

import lombok.RequiredArgsConstructor;

/**
 * Inserts a job unless one with the same idempotency key already exists. {@code ON CONFLICT DO NOTHING}
 * resolves concurrent duplicates without an exception, so it is safe inside a larger transaction
 * (a unique violation would abort the whole Postgres transaction).
 */
@Component
@RequiredArgsConstructor
public class JobInserter {
    private static final String SQL = """
        insert into jobs (id, type, payload, priority, status, attempts, max_attempts, timeout_seconds,
                          run_at, created_at, updated_at, idempotency_key, cron_schedule_id, cancel_requested)
        values (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, false)
        on conflict (idempotency_key) do nothing
        """;

    private final JdbcTemplate jdbc;

    /** @return true if inserted, false if the idempotency key was already taken */
    public boolean insertIfAbsent(Job job) {
        int rows = jdbc.update(SQL, ps -> {
            ps.setObject(1, job.getId());
            ps.setString(2, job.getType());
            ps.setString(3, job.getPayload());
            ps.setInt(4, job.getPriority());
            ps.setString(5, job.getStatus().name());
            ps.setInt(6, job.getMaxAttempts());
            ps.setInt(7, job.getTimeoutSeconds());
            ps.setObject(8, utc(job.getRunAt()));
            ps.setObject(9, utc(job.getCreatedAt()));
            ps.setObject(10, utc(job.getUpdatedAt()));
            ps.setString(11, job.getIdempotencyKey());
            if (job.getCronScheduleId() == null) {
                ps.setNull(12, Types.OTHER);
            } else {
                ps.setObject(12, job.getCronScheduleId());
            }
        });
        return rows == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
