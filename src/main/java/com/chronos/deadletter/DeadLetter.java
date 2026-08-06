package com.chronos.deadletter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.chronos.execution.JobExecution;
import com.chronos.job.Job;

import java.time.Instant;

/**
 * A permanently failed execution, parked for human inspection and manual replay.
 *
 * <p>job is denormalised alongside execution so the dead-letter list can be filtered and
 * ownership-checked without joining through job_executions on every query.
 */
@Entity
@Table(name = "dead_letters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id", nullable = false, unique = true)
    private JobExecution execution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(nullable = false)
    private String reason;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    /** Non-null once an operator replayed it; the original row is kept for the audit trail. */
    @Column(name = "replayed_at")
    private Instant replayedAt;
}
