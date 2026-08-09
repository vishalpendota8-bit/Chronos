package com.chronos.stats.dto;

import java.time.Instant;

/**
 * The "is everything all right" summary, scoped to whatever the caller can see.
 *
 * <p><b>Why the execution counts are windowed but the job counts are not.</b> Jobs are current
 * state — how many exist right now — and a window would be meaningless. Executions are events,
 * and an all-time count only ever grows, so "12,486 succeeded" tells an operator nothing about
 * whether the system is healthy <em>today</em>. The window is what turns a total into a signal.
 *
 * <p><b>{@code failed} and {@code dead} are not the same alarm.</b> {@code failed} counts
 * individual attempts that failed, most of which were then retried successfully — it measures
 * turbulence, not damage. {@code dead} means an occurrence gave up entirely, and it is the one
 * worth paging someone about.
 *
 * @param windowHours how far back the execution counts reach.
 * @param pending attempts within the window that are queued, retrying, or running — work the
 *        engine still owes. A number that climbs steadily is the signal that dispatch capacity is
 *        not keeping up with the schedule.
 * @param successRate succeeded / (succeeded + dead), as a fraction. Failed attempts are excluded
 *        from both sides on purpose: an occurrence that failed twice and then succeeded is a
 *        success, and counting its failures would make a healthy but flaky target look broken.
 *        Null when nothing has reached a terminal state in the window — a rate of "0.0" for "no
 *        data" would read as a total outage.
 * @param pendingDeadLetters dead letters not yet replayed: the operator's actual to-do list, and
 *        unlike everything else here it is all-time, because an unreplayed failure from last week
 *        is still outstanding.
 * @param nextRunAt the soonest upcoming occurrence. Null means nothing is scheduled at all —
 *        which, if unexpected, is itself the most important thing on this page.
 */
public record StatsResponse(
        Instant generatedAt,
        int windowHours,

        long totalJobs,
        long enabledJobs,
        long pausedJobs,
        long archivedJobs,

        long executionsInWindow,
        long succeeded,
        long failed,
        long dead,
        long pending,
        Double successRate,

        long pendingDeadLetters,
        Instant nextRunAt
) {
}
