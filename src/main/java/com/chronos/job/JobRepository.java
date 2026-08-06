package com.chronos.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    Page<Job> findByOwnerIdAndStatusNot(Long ownerId, JobStatus status, Pageable pageable);

    Page<Job> findByStatusNot(JobStatus status, Pageable pageable);

    /**
     * Ownership-scoped lookup. Callers use this instead of findById so a user can never read
     * another user's job by guessing an id.
     */
    Optional<Job> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    List<Job> findByStatus(JobStatus status);
}
