package com.sanvi.jobscheduler.pulsecheck.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * The 24 h part of {@link #uptime} for several monitors in one query (for the monitor list): one row per
     * monitor that has checks in the last 24 h. Scans at most 24 h of rows per monitor, on the
     * (monitor_id, checked_at) index.
     */
    @Query(value = """
        select
          monitor_id as "monitorId",
          round(100.0 * count(*) filter (where up) / count(*), 2) as "uptime24h",
          round(avg(response_ms) filter (where up))::int as "avgResponseMs24h",
          count(*) as "checks24h"
        from check_results
        where monitor_id in (:monitorIds) and checked_at >= now() - interval '24 hours'
        group by monitor_id
        """, nativeQuery = true)
    List<DailyStats> last24h(@Param("monitorIds") Collection<UUID> monitorIds);

    /**
     * Response times over the last {@code rangeSeconds}, in buckets of {@code bucketSeconds} (database clock).
     * Every bucket in the window is returned, oldest first, including empty ones (0 checks, null average), so
     * gaps in the data stay gaps on a chart. Buckets are aligned with {@code date_bin} to a fixed origin, so a
     * bucket covers the same minutes on every refresh; the first one may start a little before the window.
     * The average is of successful checks only, like {@link #uptime}. One range scan on the
     * (monitor_id, checked_at) index.
     */
    @Query(value = """
        with bounds as (
          select (:bucketSeconds * interval '1 second') as step,
                 date_bin(:bucketSeconds * interval '1 second', now() - :rangeSeconds * interval '1 second',
                          timestamptz '2000-01-01 00:00:00+00') as first_bucket
        ),
        buckets as (
          select generate_series(b.first_bucket, now(), b.step) as bucket_start from bounds b
        ),
        stats as (
          select date_bin(b.step, r.checked_at, timestamptz '2000-01-01 00:00:00+00') as bucket_start,
                 count(*) as checks,
                 count(*) filter (where not r.up) as failures,
                 round(avg(r.response_ms) filter (where r.up))::int as avg_ms,
                 max(r.response_ms) filter (where r.up) as max_ms
          from check_results r cross join bounds b
          where r.monitor_id = :monitorId and r.checked_at >= b.first_bucket
          group by 1
        )
        select extract(epoch from k.bucket_start)::bigint as "startEpochSecond",
               coalesce(s.checks, 0) as "checks",
               coalesce(s.failures, 0) as "failures",
               s.avg_ms as "avgResponseMs",
               s.max_ms as "maxResponseMs"
        from buckets k left join stats s on s.bucket_start = k.bucket_start
        order by k.bucket_start
        """, nativeQuery = true)
    List<ResponseTimeBucket> responseTimes(@Param("monitorId") UUID monitorId,
        @Param("rangeSeconds") long rangeSeconds, @Param("bucketSeconds") long bucketSeconds);

    interface ResponseTimeBucket {
        Long getStartEpochSecond();

        Long getChecks();

        Long getFailures();

        Integer getAvgResponseMs();

        Integer getMaxResponseMs();
    }

    interface DailyStats {
        UUID getMonitorId();

        Double getUptime24h();

        Integer getAvgResponseMs24h();

        Long getChecks24h();
    }

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
