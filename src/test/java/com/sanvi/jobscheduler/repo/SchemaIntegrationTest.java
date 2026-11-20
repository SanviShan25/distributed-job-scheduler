package com.sanvi.jobscheduler.repo;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;
import com.sanvi.jobscheduler.support.AbstractIntegrationTest;

class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void allTimestampColumnsAreTimestamptz() {
        List<Map<String, Object>> columns = jdbc.queryForList(
            "select column_name, data_type from information_schema.columns "
                + "where table_name = 'jobs' and data_type like 'timestamp%'");
        assertThat(columns).isNotEmpty();
        assertThat(columns).allSatisfy(c ->
            assertThat(c.get("data_type")).as(c.get("column_name").toString())
                .isEqualTo("timestamp with time zone"));
        assertThat(columns).extracting(c -> c.get("column_name"))
            .contains("run_at", "created_at", "updated_at", "lease_expires_at", "completed_at");
    }

    @Test
    void requiredIndexesExist() {
        List<String> indexes = jdbc.queryForList(
            "select indexdef from pg_indexes where tablename = 'jobs'", String.class);
        assertThat(indexes).anyMatch(d -> d.contains("(status, updated_at)"));
        assertThat(indexes).anyMatch(d -> d.contains("(status, run_at)"));
        assertThat(indexes).anyMatch(d -> d.contains("(worker_id) WHERE") && d.contains("RUNNING"));
        assertThat(indexes).anyMatch(d -> d.contains("(status, lease_expires_at) WHERE") && d.contains("RUNNING"));
        assertThat(indexes).anyMatch(d -> d.contains("UNIQUE") && d.contains("(idempotency_key)"));
    }

    @Test
    void everyEnumValueIsAcceptedByTheCheckConstraint() {
        for (JobStatus status : JobStatus.values()) {
            repo.saveAndFlush(job(status, 0, 3));
        }
        assertThat(repo.count()).isEqualTo(JobStatus.values().length);
    }

    @Test
    void checkConstraintRejectsUnknownStatuses() {
        for (String legacy : Arrays.asList("PENDING", "FAILED", "BOGUS")) {
            assertThatThrownBy(() -> jdbc.update(
                "insert into jobs (id, type, status, run_at, created_at, updated_at) values (?, 'EMAIL', ?, now(), now(), now())",
                UUID.randomUUID(), legacy))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void idempotencyKeyIsUnique() {
        Job first = job(JobStatus.QUEUED, 0, 3);
        first.setIdempotencyKey("same-key");
        repo.saveAndFlush(first);
        Job second = job(JobStatus.QUEUED, 0, 3);
        second.setIdempotencyKey("same-key");
        assertThatThrownBy(() -> repo.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void terminalTransitionsSetCompletedAt() {
        Job job = repo.saveAndFlush(job(JobStatus.QUEUED, 0, 3));
        UUID token = UUID.randomUUID();
        repo.claim(job.getId(), "w", token, 30);
        assertThat(reload(job.getId()).getCompletedAt()).isNull();
        repo.markDead(job.getId(), "w", token, "x", Instant.now());
        assertThat(reload(job.getId()).getCompletedAt()).isNotNull();
        repo.requeueDead(job.getId(), Instant.now());
        assertThat(reload(job.getId()).getCompletedAt()).isNull();
    }
}
