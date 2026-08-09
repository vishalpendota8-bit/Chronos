package com.chronos.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // ------------------------------------------------------------------ stats (M6)

    /** See {@link com.chronos.execution.JobExecutionRepository#countByStatusSince} for the why. */
    @Query("SELECT new com.chronos.job.JobStatusCount(j.status, COUNT(j)) FROM Job j GROUP BY j.status")
    List<JobStatusCount> countByStatus();

    @Query("""
            SELECT new com.chronos.job.JobStatusCount(j.status, COUNT(j))
              FROM Job j
             WHERE j.owner.id = :ownerId
             GROUP BY j.status
            """)
    List<JobStatusCount> countByStatusForOwner(@Param("ownerId") Long ownerId);

    /**
     * The soonest upcoming occurrence across the visible jobs — "when does anything happen next".
     *
     * <p>{@code MIN} over an empty set is null, which is the correct answer for an account with
     * no enabled jobs, so the return type is boxed.
     */
    @Query("SELECT MIN(j.nextRunAt) FROM Job j WHERE j.status = :status")
    Instant findEarliestNextRun(@Param("status") JobStatus status);

    @Query("SELECT MIN(j.nextRunAt) FROM Job j WHERE j.status = :status AND j.owner.id = :ownerId")
    Instant findEarliestNextRunForOwner(@Param("status") JobStatus status,
                                        @Param("ownerId") Long ownerId);
}
