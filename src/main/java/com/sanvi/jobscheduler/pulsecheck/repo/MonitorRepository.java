package com.sanvi.jobscheduler.pulsecheck.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;

import jakarta.persistence.LockModeType;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {

    /** SELECT … FOR UPDATE: serializes concurrent checks of the same monitor within a transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Monitor m where m.id = :id")
    Optional<Monitor> findByIdForUpdate(@Param("id") UUID id);

    Optional<Monitor> findByIdAndUserId(UUID id, UUID userId);

    List<Monitor> findByUserIdOrderByCreatedAtAsc(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndUrl(UUID userId, String url);

    boolean existsByUserIdAndUrlAndIdNot(UUID userId, String url, UUID id);
}
