package com.chronos.execution;

import java.util.Set;

/**
 * Lifecycle of a single execution row.
 *
 * <pre>
 *   QUEUED ──claim──> RUNNING ──2xx──> SUCCEEDED
 *      ▲                  │
 *      │                  ├──retryable failure, attempts left──> FAILED
 *      │                  │        (a new row is inserted as RETRY_SCHEDULED)
 *      │                  └──4xx or attempts exhausted─────────> DEAD (+ dead_letters row)
 *      │
 *   RETRY_SCHEDULED ──claim──> RUNNING ...
 * </pre>
 */
public enum ExecutionStatus {
    /** First attempt for a cron occurrence, waiting for its run_at. */
    QUEUED,
    /** A retry attempt, waiting for its backed-off run_at. */
    RETRY_SCHEDULED,
    /** Claimed by a scheduler node and currently dispatching. */
    RUNNING,
    SUCCEEDED,
    /** This attempt failed; a retry may exist as a separate row. */
    FAILED,
    /** Terminal failure. Has a matching dead_letters row. */
    DEAD;

    /** The two states the poller is allowed to claim. Kept here so the set has one definition. */
    public static final Set<ExecutionStatus> CLAIMABLE = Set.of(QUEUED, RETRY_SCHEDULED);

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD;
    }
}
