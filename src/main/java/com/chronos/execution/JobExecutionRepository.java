package com.chronos.execution;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface JobExecutionRepository extends JpaRepository<JobExecution, Long> {

    Page<JobExecution> findByJobIdOrderByScheduledForDesc(Long jobId, Pageable pageable);

    boolean existsByJobIdAndScheduledForAndAttemptNo(Long jobId, Instant scheduledFor, int attemptNo);

    Optional<JobExecution> findFirstByJobIdOrderByScheduledForDesc(Long jobId);

    long countByJobIdAndStatus(Long jobId, ExecutionStatus status);

    // The claiming query (FOR UPDATE SKIP LOCKED) and the reaper query land here in M4/M6.
}
