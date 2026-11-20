package com.sanvi.jobscheduler.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.sanvi.jobscheduler.domain.Job;
import com.sanvi.jobscheduler.domain.JobStatus;

public interface JobRepository extends JpaRepository<Job, UUID> {
    List<Job> findByStatus(JobStatus status);
    List<Job> findByStatusAndWorkerIdNotIn(JobStatus status, Collection<String> aliveWorkers);
    List<Job> findByStatusAndUpdatedAtBeforeAndRunAtBefore(JobStatus status, Instant updatedAt, Instant runAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING, "
        + "j.workerId = :workerId, j.attempts = j.attempts + 1, j.updatedAt = :now "
        + "where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.QUEUED")
    int claim(@Param("id") UUID id, @Param("workerId") String workerId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.SUCCEEDED, "
        + "j.updatedAt = :now where j.id = :id and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING "
        + "and j.workerId = :workerId")
    int markSucceeded(@Param("id") UUID id, @Param("workerId") String workerId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.QUEUED, "
        + "j.lastError = :lastError, j.updatedAt = :now where j.id = :id "
        + "and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING and j.workerId = :workerId")
    int markForRetry(@Param("id") UUID id, @Param("workerId") String workerId,
        @Param("lastError") String lastError, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Job j set j.status = com.sanvi.jobscheduler.domain.JobStatus.DEAD, "
        + "j.lastError = :lastError, j.updatedAt = :now where j.id = :id "
        + "and j.status = com.sanvi.jobscheduler.domain.JobStatus.RUNNING and j.workerId = :workerId")
    int markDead(@Param("id") UUID id, @Param("workerId") String workerId,
        @Param("lastError") String lastError, @Param("now") Instant now);

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