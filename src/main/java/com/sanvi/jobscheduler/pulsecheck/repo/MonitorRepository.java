package com.sanvi.jobscheduler.pulsecheck.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sanvi.jobscheduler.pulsecheck.domain.Monitor;

public interface MonitorRepository extends JpaRepository<Monitor, UUID> {
}
