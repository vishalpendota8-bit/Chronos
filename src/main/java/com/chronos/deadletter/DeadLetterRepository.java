package com.chronos.deadletter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    Page<DeadLetter> findByJobOwnerIdOrderByFailedAtDesc(Long ownerId, Pageable pageable);

    Page<DeadLetter> findByJobOwnerIdAndReplayedAtIsNullOrderByFailedAtDesc(Long ownerId, Pageable pageable);

    Page<DeadLetter> findByReplayedAtIsNullOrderByFailedAtDesc(Pageable pageable);

    Optional<DeadLetter> findByExecutionId(Long executionId);
}
