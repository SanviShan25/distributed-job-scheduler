package com.sanvi.jobscheduler.pulsecheck.repo;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {

    long countByMonitorId(UUID monitorId);

    /** When the current failure streak began: the oldest of the monitor's latest {@code streak} results. */
    @Query(value = "select min(checked_at) from (select checked_at from check_results where monitor_id = :monitorId "
        + "order by checked_at desc limit :streak) latest", nativeQuery = true)
    Instant streakStartedAt(@Param("monitorId") UUID monitorId, @Param("streak") int streak);
}
