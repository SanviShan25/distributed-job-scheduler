package com.sanvi.jobscheduler.repo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sanvi.jobscheduler.domain.JobSchedule;

public interface JobScheduleRepository extends JpaRepository<JobSchedule, UUID> {

    boolean existsByName(String name);

    /**
     * Due schedules, row-locked for the current transaction. SKIP LOCKED lets several API instances
     * run the scheduler concurrently: each schedule is handled by exactly one of them.
     */
    @Query(value = "select * from job_schedules where enabled and next_run_at <= :now "
        + "order by next_run_at limit :limit for update skip locked", nativeQuery = true)
    List<JobSchedule> lockDue(@Param("now") Instant now, @Param("limit") int limit);
}
