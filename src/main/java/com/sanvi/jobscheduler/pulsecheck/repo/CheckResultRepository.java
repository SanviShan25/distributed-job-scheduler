package com.sanvi.jobscheduler.pulsecheck.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sanvi.jobscheduler.pulsecheck.domain.CheckResult;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {
}
