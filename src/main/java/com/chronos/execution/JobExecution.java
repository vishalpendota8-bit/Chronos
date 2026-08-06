package com.chronos.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.chronos.job.Job;

import java.time.Instant;

@Entity
@Table(name = "job_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobExecution {

    /** Also sent to the target as the X-Idempotency-Key header, so it must never be reused. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    /** 1 for the first attempt. Drives the backoff exponent from M5 on. */
    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    /**
     * The cron occurrence this row belongs to. Stays fixed across retries, which is what makes
     * (job_id, scheduled_for, attempt_no) a usable uniqueness key against duplicate enqueues.
     */
    @Column(name = "scheduled_for", nullable = false)
    private Instant scheduledFor;

    /** When this attempt becomes eligible. Equals scheduledFor on attempt 1, later on retries. */
    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    /** Set when a poller wins the row under SKIP LOCKED. Non-null proves the claim happened. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /** Set immediately before the HTTP call; the reaper measures its timeout from this. */
    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "response_code")
    private Integer responseCode;

    /** Truncated response body — enough to debug with, without storing unbounded payloads. */
    @Column(name = "response_snippet")
    private String responseSnippet;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
