package com.sanvi.jobscheduler.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;

/**
 * Every state transition is a conditional UPDATE: it only applies when the row is still in the
 * state the caller observed, and returns the number of rows changed (0 = lost the race).
 */
public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {
    List<Job> findByStatus(JobStatus status);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    /**
     * RUNNING jobs whose lease has expired by the <em>database</em> clock. A NULL lease only exists on rows
     * that were RUNNING before leases were introduced (V2) and is treated as expired.
     */
    @Query(value = "select * from jobs where status = 'RUNNING' "
        + "and (lease_expires_at is null or lease_expires_at < now())", nativeQuery = true)
    List<Job> findRunningWithExpiredLease();

    /** Keyset page of jobs in {@code status} not touched since {@code updatedBefore}. */
    @Query("select j from Job j where j.status = :status and j.updatedAt < :updatedBefore "
        + "and j.id > :afterId order by j.id")
    List<Job> findStalePage(@Param("status") JobStatus status, @Param("updatedBefore") Instant updatedBefore,
        @Param("afterId") UUID afterId, Limit limit);

    /**
     * Claims a job for {@code workerId}. QUEUED jobs are always claimable; SCHEDULED jobs only once
     * {@code run_at <= now()}, so a stale ready-queue entry for a future retry cannot run early.
     * The lease is computed with the database clock, so JVM clock skew cannot shorten or extend it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update jobs set status = 'RUNNING', worker_id = :workerId, attempts = attempts + 1, "
        + "lease_token = :token, lease_expires_at = now() + make_interval(secs => :leaseSeconds), "
        + "updated_at = now() "
        + "where id = :id and attempts < max_attempts "
        + "and (status = 'QUEUED' or (status = 'SCHEDULED' and run_at <= now()))", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("leaseSeconds") double leaseSeconds);

    /**
     * Extends the lease to database {@code now() + leaseSeconds}. Returns 0 if ownership was lost
     * <em>or</em> a cancel was requested.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update jobs set lease_expires_at = now() + make_interval(secs => :leaseSeconds), "
        + "updated_at = now() where id = :id and status = 'RUNNING' and worker_id = :workerId "
        + "and lease_token = :token and cancel_requested = false", nativeQuery = true)
    int renewLease(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("leaseSeconds") double leaseSeconds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.SUCCEEDED, "
        + "j.leaseToken = null, j.leaseExpiresAt = null, j.completedAt = :now, j.updatedAt = :now "
        + "where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING "
        + "and j.workerId = :workerId and j.leaseToken = :token")
    int markSucceeded(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.SCHEDULED, "
        + "j.lastError = :lastError, j.runAt = :runAt, j.leaseToken = null, j.leaseExpiresAt = null, "
        + "j.updatedAt = :now where j.id = :id "
        + "and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING and j.workerId = :workerId "
        + "and j.leaseToken = :token")
    int markForRetry(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("lastError") String lastError, @Param("runAt") Instant runAt, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.DEAD, "
        + "j.lastError = :lastError, j.leaseToken = null, j.leaseExpiresAt = null, j.completedAt = :now, "
        + "j.updatedAt = :now "
        + "where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING "
        + "and j.workerId = :workerId and j.leaseToken = :token")
    int markDead(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("lastError") String lastError, @Param("now") Instant now);

    /** Shutdown interrupted the job: hand it back without charging the attempt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.QUEUED, "
        + "j.attempts = j.attempts - 1, j.leaseToken = null, j.leaseExpiresAt = null, j.updatedAt = :now "
        + "where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING "
        + "and j.workerId = :workerId and j.leaseToken = :token")
    int releaseForShutdown(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("now") Instant now);

    /**
     * Reaper recovery of a RUNNING job to QUEUED, DEAD or CANCELLED. Applies only if ownership is
     * unchanged <em>and</em> the lease is still expired by the database clock, so a lease renewed after
     * the reaper read the row is never recovered.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update jobs set status = :newStatus, last_error = :lastError, lease_token = null, "
        + "lease_expires_at = null, completed_at = case when :terminal then now() else null end, "
        + "updated_at = now() "
        + "where id = :id and status = 'RUNNING' and worker_id = :workerId and lease_token = :token "
        + "and (lease_expires_at is null or lease_expires_at < now())", nativeQuery = true)
    int recoverExpired(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("newStatus") String newStatus, @Param("lastError") String lastError,
        @Param("terminal") boolean terminal);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.QUEUED, j.attempts = 0, "
        + "j.lastError = null, j.workerId = null, j.completedAt = null, j.runAt = :now, j.updatedAt = :now "
        + "where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.DEAD")
    int requeueDead(@Param("id") UUID id, @Param("now") Instant now);

    /** Sweeper: bump updatedAt only if nobody changed the row since it was read. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.updatedAt = :now where j.id = :id and j.status = :status "
        + "and j.updatedAt = :expectedUpdatedAt")
    int touchIfUnchanged(@Param("id") UUID id, @Param("status") JobStatus status,
        @Param("expectedUpdatedAt") Instant expectedUpdatedAt, @Param("now") Instant now);

    /** QUEUED/SCHEDULED jobs that can never be claimed again (attempts exhausted) go to DEAD. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.DEAD, "
        + "j.lastError = 'max attempts exhausted', j.completedAt = :now, j.updatedAt = :now "
        + "where j.status in (com.sanvi.jobscheduler.domain.JobStatus.QUEUED, "
        + "com.sanvi.jobscheduler.domain.JobStatus.SCHEDULED) and j.attempts >= j.maxAttempts")
    int markExhaustedQueuedJobsDead(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.CANCELLED, "
        + "j.completedAt = :now, j.updatedAt = :now where j.id = :id "
        + "and j.status in (com.sanvi.jobscheduler.domain.JobStatus.QUEUED, "
        + "com.sanvi.jobscheduler.domain.JobStatus.SCHEDULED)")
    int cancelPending(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.cancelRequested = true, j.updatedAt = :now where j.id = :id "
        + "and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING")
    int requestCancel(@Param("id") UUID id, @Param("now") Instant now);

    /** The worker acknowledges a cancel request for the job it owns. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.CANCELLED, "
        + "j.leaseToken = null, j.leaseExpiresAt = null, j.completedAt = :now, j.updatedAt = :now "
        + "where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING "
        + "and j.workerId = :workerId and j.leaseToken = :token")
    int markCancelled(@Param("id") UUID id, @Param("workerId") String workerId, @Param("token") UUID token,
        @Param("now") Instant now);

    /** Deletes one batch of finished jobs older than {@code cutoff}; returns rows deleted. */
    @Modifying
    @Transactional
    @Query(value = "delete from jobs where id in (select id from jobs where status in ('SUCCEEDED', 'CANCELLED') "
        + "and completed_at < :cutoff limit :batchSize)", nativeQuery = true)
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Query("select j.status as status, count(j) as count from Job j group by j.status")
    List<StatusCount> countJobsByStatus();

    @Query("select j.workerId as workerId, count(j) as count from Job j "
        + "where j.status = :status group by j.workerId")
    List<WorkerCount> countJobsByWorkerForStatus(@Param("status") JobStatus status);

    interface StatusCount {
        JobStatus getStatus();

        Long getCount();
    }

    interface WorkerCount {
        String getWorkerId();

        Long getCount();
    }
}
