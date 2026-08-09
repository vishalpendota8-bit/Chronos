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

    /**
     * <b>The reaper's query.</b> Claims executions that have been RUNNING too long.
     *
     * <p><b>What it is for.</b> RUNNING is the one status no poller will ever look at again — by
     * design, since that is what stops two nodes running the same attempt. So a row that reaches
     * RUNNING and never leaves is stuck forever. That happens whenever a node dies mid-dispatch:
     * the claim committed, the process vanished, and nothing is left to write a result. Worse,
     * the overlap guard in {@link com.chronos.scheduler.JobOccurrenceEnqueuer} treats an
     * unfinished attempt as "the job is busy", so one abandoned row silently stops that job
     * from ever being scheduled again. The reaper is what makes a crashed node survivable.
     *
     * <p><b>Why {@code COALESCE(started_at, claimed_at)}.</b> Two different stalls have to be
     * caught. Usually the row was claimed, {@code started_at} was stamped, and the HTTP call
     * hung — measure from {@code started_at}. But a node can also die in the window between the
     * claim committing and the dispatch thread stamping {@code started_at}, leaving it null; then
     * {@code claimed_at} is the only evidence of when the row went RUNNING. Falling back to it
     * means such a row is reaped rather than stuck forever.
     *
     * <p><b>Why the join.</b> The timeout is per job ({@code jobs.timeout_sec}), so there is no
     * single cutoff instant — each row has its own. {@code make_interval} builds that per-row
     * deadline in SQL, which keeps the filtering in the database instead of loading every RUNNING
     * row into Java to test it.
     *
     * <p><b>Why {@code FOR UPDATE OF e}.</b> Plain {@code FOR UPDATE} on a join locks the matched
     * rows of <em>every</em> table in it — here, the {@code jobs} rows too. That would collide
     * with the enqueuer, which updates {@code jobs.next_run_at}, and turn a background cleanup
     * into a source of lock contention on the hot path. {@code OF e} restricts locking to the
     * execution rows, which are the only ones being modified. {@code SKIP LOCKED} then does its
     * usual job: two nodes reaping at once take disjoint sets.
     */
    @Query(value = """
            SELECT e.* FROM job_executions e
              JOIN jobs j ON j.id = e.job_id
             WHERE e.status = 'RUNNING'
               AND COALESCE(e.started_at, e.claimed_at)
                     + make_interval(secs => j.timeout_sec + :graceSec) < CAST(:now AS timestamptz)
             ORDER BY COALESCE(e.started_at, e.claimed_at)
             LIMIT :limit
               FOR UPDATE OF e SKIP LOCKED
            """, nativeQuery = true)
    List<JobExecution> claimTimedOut(@Param("now") Instant now,
                                     @Param("graceSec") int graceSec,
                                     @Param("limit") int limit);

    // ------------------------------------------------------------------ stats (M6)

    /**
     * Execution counts grouped by status, for every job, within a time window.
     *
     * <p><b>New concept — a JPQL constructor expression.</b> {@code SELECT new Some.Record(...)}
     * maps each result row straight into a record. The alternative for a GROUP BY is
     * {@code List<Object[]>}, where every read is an unchecked cast against a column order that
     * nothing enforces; this way a change to the query that breaks the shape is a compile error.
     *
     * <p><b>Why two methods instead of one with a nullable owner filter.</b> A
     * {@code (:ownerId IS NULL OR j.owner.id = :ownerId)} predicate is the usual trick, but with
     * a null argument the JDBC driver has no type to infer for the parameter, and it also
     * produces one query plan that has to serve both shapes. Two explicit queries read better
     * and each plans for what it actually does.
     */
    @Query("""
            SELECT new com.chronos.execution.ExecutionStatusCount(e.status, COUNT(e))
              FROM JobExecution e
             WHERE e.scheduledFor >= :since
             GROUP BY e.status
            """)
    List<ExecutionStatusCount> countByStatusSince(@Param("since") Instant since);

    @Query("""
            SELECT new com.chronos.execution.ExecutionStatusCount(e.status, COUNT(e))
              FROM JobExecution e
             WHERE e.scheduledFor >= :since AND e.job.owner.id = :ownerId
             GROUP BY e.status
            """)
    List<ExecutionStatusCount> countByStatusSinceForOwner(@Param("since") Instant since,
                                                          @Param("ownerId") Long ownerId);

    /** All-time status breakdown for one job, for the per-job stats view. */
    @Query("""
            SELECT new com.chronos.execution.ExecutionStatusCount(e.status, COUNT(e))
              FROM JobExecution e
             WHERE e.job.id = :jobId
             GROUP BY e.status
            """)
    List<ExecutionStatusCount> countByStatusForJob(@Param("jobId") Long jobId);

    /** The most recent attempt that actually finished — "when did this last run, and how". */
    Optional<JobExecution> findFirstByJobIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(Long jobId);

    /**
     * Mean wall-clock duration of this job's completed attempts, in seconds.
     *
     * <p>Native because the arithmetic is timestamp subtraction: JPQL has no portable way to
     * express "the difference between two instants as a number". {@code EXTRACT(EPOCH FROM ...)}
     * turns the resulting interval into seconds.
     *
     * @return null when the job has never completed an attempt — {@code AVG} over no rows is
     *         null, not zero, and that distinction is worth preserving in the response.
     */
    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (finished_at - started_at)))
              FROM job_executions
             WHERE job_id = :jobId
               AND started_at IS NOT NULL
               AND finished_at IS NOT NULL
            """, nativeQuery = true)
    Double averageDurationSeconds(@Param("jobId") Long jobId);
}
