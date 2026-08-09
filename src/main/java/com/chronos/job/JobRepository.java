package com.chronos.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    Page<Job> findByOwnerIdAndStatusNot(Long ownerId, JobStatus status, Pageable pageable);

    Page<Job> findByStatusNot(JobStatus status, Pageable pageable);

    /** Used by ?includeArchived=true — the only listing that shows soft-deleted jobs. */
    Page<Job> findByOwnerId(Long ownerId, Pageable pageable);

    /**
     * Ownership-scoped lookup. Callers use this instead of findById so a user can never read
     * another user's job by guessing an id.
     */
    Optional<Job> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    /** Rename check: the same name on a *different* job means a collision. */
    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(Long ownerId, String name, Long id);

    List<Job> findByStatus(JobStatus status);

    /**
     * The enqueuer's query: jobs whose next occurrence has arrived.
     *
     * <p>No SKIP LOCKED here. Two nodes may well pick up the same job, and that is fine — the
     * {@code (job_id, scheduled_for, attempt_no)} unique index means only one of them can
     * insert the execution row, and the loser treats the violation as "already enqueued". The
     * database constraint is the coordination mechanism.
     */
    List<Job> findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            JobStatus status, Instant cutoff, Pageable pageable);
}
