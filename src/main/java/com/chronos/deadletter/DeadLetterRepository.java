package com.chronos.deadletter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    /*
     * @EntityGraph on the listing queries fetches job and execution in the same SELECT. Without
     * it, rendering jobName and attemptNo for a page of 20 dead letters would fire 40 extra
     * queries as each lazy proxy is touched — the classic N+1.
     */

    @EntityGraph(attributePaths = {"job", "execution"})
    Page<DeadLetter> findByJobOwnerIdOrderByFailedAtDesc(Long ownerId, Pageable pageable);

    @EntityGraph(attributePaths = {"job", "execution"})
    Page<DeadLetter> findByJobOwnerIdAndReplayedAtIsNullOrderByFailedAtDesc(Long ownerId, Pageable pageable);

    @EntityGraph(attributePaths = {"job", "execution"})
    Page<DeadLetter> findByReplayedAtIsNullOrderByFailedAtDesc(Pageable pageable);

    /** Admin listing including already-replayed entries. */
    @EntityGraph(attributePaths = {"job", "execution"})
    Page<DeadLetter> findAllByOrderByFailedAtDesc(Pageable pageable);

    Optional<DeadLetter> findByExecutionId(Long executionId);

    // ------------------------------------------------------------------ stats (M6)

    /*
     * "Pending" means not yet replayed — the number an operator should act on. These are counts,
     * so no @EntityGraph: COUNT never touches the lazy associations.
     */

    long countByReplayedAtIsNull();

    long countByJobOwnerIdAndReplayedAtIsNull(Long ownerId);

    long countByJobIdAndReplayedAtIsNull(Long jobId);
}
