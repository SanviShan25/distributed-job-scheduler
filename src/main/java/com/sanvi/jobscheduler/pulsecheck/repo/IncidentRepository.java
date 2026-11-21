package com.sanvi.jobscheduler.pulsecheck.repo;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sanvi.jobscheduler.pulsecheck.domain.Incident;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Optional<Incident> findByMonitorIdAndResolvedAtIsNull(UUID monitorId);

    long countByMonitorId(UUID monitorId);

    Page<Incident> findByMonitorId(UUID monitorId, Pageable pageable);

    /**
     * Opens an incident unless one is already open. Relies on the partial unique index
     * {@code uq_incidents_one_open_per_monitor}, so a retried or concurrent check can never create a
     * second open incident.
     *
     * @return 1 if an incident was opened, 0 if one was already open
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "insert into incidents (id, monitor_id, started_at, cause) values (:id, :monitorId, :startedAt, :cause) "
        + "on conflict (monitor_id) where resolved_at is null do nothing", nativeQuery = true)
    int openIfNoneOpen(@Param("id") UUID id, @Param("monitorId") UUID monitorId,
        @Param("startedAt") Instant startedAt, @Param("cause") String cause);

    /** @return 1 if an open incident was resolved, 0 if none was open */
    @Modifying(flushAutomatically = true)
    @Query(value = "update incidents set resolved_at = greatest(:resolvedAt, started_at) "
        + "where monitor_id = :monitorId and resolved_at is null", nativeQuery = true)
    int resolveOpen(@Param("monitorId") UUID monitorId, @Param("resolvedAt") Instant resolvedAt);
}
