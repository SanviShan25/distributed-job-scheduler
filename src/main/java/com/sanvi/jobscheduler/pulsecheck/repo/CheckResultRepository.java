package com.sanvi.jobscheduler.pulsecheck.repo;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {

    long countByMonitorId(UUID monitorId);

    Page<CheckResult> findByMonitorIdAndCheckedAtGreaterThanEqualAndCheckedAtLessThan(UUID monitorId,
        Instant from, Instant to, Pageable pageable);

    /** When the current failure streak began: the oldest of the monitor's latest {@code streak} results. */
    @Query(value = "select min(checked_at) from (select checked_at from check_results where monitor_id = :monitorId "
        + "order by checked_at desc limit :streak) latest", nativeQuery = true)
    Instant streakStartedAt(@Param("monitorId") UUID monitorId, @Param("streak") int streak);

    /**
     * Uptime % over the last 24 h / 7 d / 30 d and the average response time of successful checks in the
     * last 24 h, in one indexed scan (database clock). Values are null when there are no checks in a window.
     */
    @Query(value = """
        select
          round(100.0 * count(*) filter (where up and checked_at >= now() - interval '24 hours')
                / nullif(count(*) filter (where checked_at >= now() - interval '24 hours'), 0), 2) as "uptime24h",
          round(100.0 * count(*) filter (where up and checked_at >= now() - interval '7 days')
                / nullif(count(*) filter (where checked_at >= now() - interval '7 days'), 0), 2) as "uptime7d",
          round(100.0 * count(*) filter (where up)
                / nullif(count(*), 0), 2) as "uptime30d",
          round(avg(response_ms) filter (where up and checked_at >= now() - interval '24 hours'))::int
                as "avgResponseMs24h",
          count(*) filter (where checked_at >= now() - interval '24 hours') as "checks24h"
        from check_results
        where monitor_id = :monitorId and checked_at >= now() - interval '30 days'
        """, nativeQuery = true)
    UptimeStats uptime(@Param("monitorId") UUID monitorId);

    interface UptimeStats {
        Double getUptime24h();

        Double getUptime7d();

        Double getUptime30d();

        Integer getAvgResponseMs24h();

        Long getChecks24h();
    }

    /** Deletes one batch of results older than {@code cutoff}; returns the number deleted. */
    @Modifying
    @Transactional
    @Query(value = "delete from check_results where id in (select id from check_results where checked_at < :cutoff "
        + "limit :batchSize)", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
