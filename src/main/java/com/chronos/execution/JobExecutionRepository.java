package com.chronos.execution;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

    Page<JobExecution> findByJobIdOrderByScheduledForDesc(Long jobId, Pageable pageable);

    boolean existsByJobIdAndScheduledForAndAttemptNo(Long jobId, Instant scheduledFor, int attemptNo);

    Optional<JobExecution> findFirstByJobIdOrderByScheduledForDesc(Long jobId);

    long countByJobIdAndStatus(Long jobId, ExecutionStatus status);

    /** Overlap guard: is there already an unfinished attempt for this job? */
    boolean existsByJobIdAndStatusIn(Long jobId, Collection<ExecutionStatus> statuses);

    /**
     * Highest attempt number recorded for one occurrence.
     *
     * <p>A manual retry has to pick an attempt number that does not collide with any row already
     * written for the same occurrence, and it cannot simply assume "the failed attempt + 1" —
     * that row may already exist from an automatic retry.
     */
    @Query("SELECT MAX(e.attemptNo) FROM JobExecution e "
            + "WHERE e.job.id = :jobId AND e.scheduledFor = :scheduledFor")
    Integer findMaxAttemptNo(@Param("jobId") Long jobId, @Param("scheduledFor") Instant scheduledFor);

    /**
     * <b>The heart of the distributed scheduler.</b> Claims a batch of due executions.
     *
     * <p><b>New concept — {@code FOR UPDATE SKIP LOCKED}:</b> {@code FOR UPDATE} takes a row
     * lock on everything the SELECT returns, held until the transaction ends. Normally a second
     * transaction asking for the same rows would <em>block</em> waiting for those locks, and
     * then — this is the important part — re-read them and get the same rows back, so both
     * nodes would dispatch the same job.
     *
     * <p>{@code SKIP LOCKED} changes that: instead of waiting, the second transaction silently
     * ignores rows another transaction has locked and takes the next unlocked ones. Two nodes
     * polling at the same instant therefore come away with <em>disjoint</em> batches, with no
     * coordination, no leader election, and no distributed lock service. This one clause is why
     * Chronos can be scaled by running more copies of the same process.
     *
     * <p>The locks disappear when the claiming transaction commits — which happens immediately
     * after the rows are marked RUNNING, long before any HTTP call is made. The RUNNING status,
     * not the lock, is what stops another node picking them up afterwards.
     *
     * <p>Written as a native query because JPQL cannot express SKIP LOCKED;
     * {@code @Lock(PESSIMISTIC_WRITE)} would give plain FOR UPDATE and the blocking behaviour
     * described above.
     *
     * @param limit keep this bounded — a node that claims everything leaves nothing for its peers.
     */
    @Query(value = """
            SELECT * FROM job_executions
             WHERE status IN ('QUEUED', 'RETRY_SCHEDULED')
               AND run_at <= :now
             ORDER BY run_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<JobExecution> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
