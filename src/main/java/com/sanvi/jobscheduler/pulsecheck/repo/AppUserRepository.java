package com.sanvi.jobscheduler.pulsecheck.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sanvi.jobscheduler.pulsecheck.domain.AppUser;

import jakarta.persistence.LockModeType;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);

    /** The account exists and is not an expired demo sandbox (database clock), i.e. it may still be used. */
    @Query("select count(u) > 0 from AppUser u where u.id = :id "
        + "and (u.demo = false or u.demoExpiresAt > current_timestamp)")
    boolean isActive(@Param("id") UUID id);

    /** Locks the user row: serializes monitor creation per user so the monitor limit cannot be raced. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);
}
