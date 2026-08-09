package com.chronos.stats;

import com.chronos.common.NotFoundException;
import com.chronos.deadletter.DeadLetterRepository;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.ExecutionStatusCount;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import com.chronos.job.JobStatusCount;
import com.chronos.security.ChronosUserDetails;
import com.chronos.stats.dto.JobStatsResponse;
import com.chronos.stats.dto.StatsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers behind the dashboard's top bar and each job's detail page.
 *
 * <p><b>Everything is counted in the database, never in Java.</b> The temptation with a stats
 * endpoint is to load the rows and stream over them; that works beautifully on a laptop with
 * fifty executions and falls over on a production table with millions, and the failure arrives
 * as an OutOfMemoryError rather than a slow response. Two {@code GROUP BY} queries return one
 * row per status no matter how large the table is.
 *
 * <p><b>Ownership follows the same rule as everywhere else:</b> an ADMIN's numbers span the whole
 * system, a USER's cover only their own jobs. That is enforced by choosing a different query, not
 * by filtering afterwards — so there is no path where a total is computed over rows the caller
 * may not see.
 */
@Service
public class StatsService {

    /** Sane bounds for the window: an hour is the shortest useful view, 30 days the longest. */
    private static final int MIN_WINDOW_HOURS = 1;
    private static final int MAX_WINDOW_HOURS = 24 * 30;

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final DeadLetterRepository deadLetters;

    public StatsService(JobRepository jobs, JobExecutionRepository executions,
                        DeadLetterRepository deadLetters) {
        this.jobs = jobs;
        this.executions = executions;
        this.deadLetters = deadLetters;
    }

    // ------------------------------------------------------------------ system

    @Transactional(readOnly = true)
    public StatsResponse overview(ChronosUserDetails caller, int requestedWindowHours) {
        int windowHours = clampWindow(requestedWindowHours);
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(windowHours));

        boolean admin = caller.isAdmin();

        Map<JobStatus, Long> jobCounts = jobStatusCounts(
                admin ? jobs.countByStatus() : jobs.countByStatusForOwner(caller.id()));

        Map<ExecutionStatus, Long> execCounts = executionStatusCounts(admin
                ? executions.countByStatusSince(since)
                : executions.countByStatusSinceForOwner(since, caller.id()));

        long succeeded = execCounts.getOrDefault(ExecutionStatus.SUCCEEDED, 0L);
        long failed = execCounts.getOrDefault(ExecutionStatus.FAILED, 0L);
        long dead = execCounts.getOrDefault(ExecutionStatus.DEAD, 0L);
        long pending = pendingOf(execCounts);

        return new StatsResponse(
                now,
                windowHours,

                jobCounts.values().stream().mapToLong(Long::longValue).sum(),
                jobCounts.getOrDefault(JobStatus.ENABLED, 0L),
                jobCounts.getOrDefault(JobStatus.PAUSED, 0L),
                jobCounts.getOrDefault(JobStatus.ARCHIVED, 0L),

                succeeded + failed + dead + pending,
                succeeded,
                failed,
                dead,
                pending,
                successRate(succeeded, dead),

                admin
                        ? deadLetters.countByReplayedAtIsNull()
                        : deadLetters.countByJobOwnerIdAndReplayedAtIsNull(caller.id()),

                admin
                        ? jobs.findEarliestNextRun(JobStatus.ENABLED)
                        : jobs.findEarliestNextRunForOwner(JobStatus.ENABLED, caller.id()));
    }

    // ------------------------------------------------------------------ one job

    @Transactional(readOnly = true)
    public JobStatsResponse forJob(Long jobId, ChronosUserDetails caller) {
        Job job = (caller.isAdmin()
                ? jobs.findById(jobId)
                : jobs.findByIdAndOwnerId(jobId, caller.id()))
                .orElseThrow(() -> NotFoundException.of("Job", jobId));

        Map<ExecutionStatus, Long> counts =
                executionStatusCounts(executions.countByStatusForJob(jobId));

        long succeeded = counts.getOrDefault(ExecutionStatus.SUCCEEDED, 0L);
        long failed = counts.getOrDefault(ExecutionStatus.FAILED, 0L);
        long dead = counts.getOrDefault(ExecutionStatus.DEAD, 0L);
        long pending = pendingOf(counts);

        JobExecution lastRun = executions
                .findFirstByJobIdAndFinishedAtIsNotNullOrderByFinishedAtDesc(jobId)
                .orElse(null);

        return new JobStatsResponse(
                job.getId(),
                job.getName(),
                job.getStatus(),
                job.getNextRunAt(),

                succeeded + failed + dead + pending,
                succeeded,
                failed,
                dead,
                pending,
                successRate(succeeded, dead),
                executions.averageDurationSeconds(jobId),

                deadLetters.countByJobIdAndReplayedAtIsNull(jobId),
                lastRun == null ? null : lastRun.getFinishedAt(),
                lastRun == null ? null : lastRun.getStatus());
    }

    // ------------------------------------------------------------------ internals

    /**
     * A status the caller supplied but the query never returned means zero rows, not a missing
     * value — so callers read through {@code getOrDefault(status, 0L)} rather than risking a null.
     * An EnumMap because the keys are an enum: it is backed by an array indexed by ordinal, so
     * lookups are array accesses with no hashing at all.
     */
    private Map<ExecutionStatus, Long> executionStatusCounts(List<ExecutionStatusCount> rows) {
        Map<ExecutionStatus, Long> counts = new EnumMap<>(ExecutionStatus.class);
        for (ExecutionStatusCount row : rows) {
            counts.put(row.status(), row.count());
        }
        return counts;
    }

    private Map<JobStatus, Long> jobStatusCounts(List<JobStatusCount> rows) {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatusCount row : rows) {
            counts.put(row.status(), row.count());
        }
        return counts;
    }

    /** Everything the engine still owes: not yet claimed, backing off, or mid-dispatch. */
    private long pendingOf(Map<ExecutionStatus, Long> counts) {
        return counts.getOrDefault(ExecutionStatus.QUEUED, 0L)
                + counts.getOrDefault(ExecutionStatus.RETRY_SCHEDULED, 0L)
                + counts.getOrDefault(ExecutionStatus.RUNNING, 0L);
    }

    /**
     * Succeeded over succeeded-plus-dead. See {@code StatsResponse#successRate} for why the
     * intermediate FAILED attempts are excluded from both sides.
     *
     * @return null rather than 0.0 when nothing has reached a terminal state, so the dashboard
     *         can render "—" instead of a red zero for a system that simply has not run yet.
     */
    private Double successRate(long succeeded, long dead) {
        long terminal = succeeded + dead;
        return terminal == 0 ? null : (double) succeeded / terminal;
    }

    private int clampWindow(int requested) {
        return Math.max(MIN_WINDOW_HOURS, Math.min(MAX_WINDOW_HOURS, requested));
    }
}
