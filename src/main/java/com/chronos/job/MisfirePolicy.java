package com.chronos.job;

/**
 * What to do with an occurrence that is discovered <em>late</em>.
 *
 * <p><b>What "late" means here.</b> The enqueuer materialises an occurrence when
 * {@code next_run_at} has arrived. Normally that happens within one sweep interval of the
 * scheduled instant. It can happen much later for two reasons that have nothing to do with the
 * job itself:
 *
 * <ul>
 *   <li>the cluster was down (deploy, outage, everything crashed) and nobody swept;</li>
 *   <li>the previous occurrence was still running, so the overlap guard in
 *       {@link com.chronos.scheduler.JobOccurrenceEnqueuer} deferred this one until it finished.</li>
 * </ul>
 *
 * <p>Once lateness exceeds {@code chronos.scheduler.misfire-threshold-sec}, "run it" and "forget
 * it" are both defensible, and only the job's owner knows which. Hence a per-job setting.
 *
 * <p><b>Why there is no third "run every occurrence I missed" policy.</b> It is the obvious
 * option and it is the wrong one for this design. A job idle for a day on a minutely schedule
 * would owe 1,440 runs; the overlap guard permits one attempt at a time, so they would drain
 * one at a time for hours, each one firing with a {@code scheduled_for} long in the past — and
 * the whole time, the job's real current schedule would be blocked behind the backlog. A catch-up
 * mode only makes sense with a bound on how far back to catch up and permission to run
 * occurrences concurrently, which is a different engine. Skipping is the honest version of
 * "we did not run while we were down".
 */
public enum MisfirePolicy {

    /**
     * Run the late occurrence anyway, immediately.
     *
     * <p>The right default for the common case — one occurrence, a little late — and for jobs
     * whose work is cumulative ("send everything unsent"), where running late still does the
     * job correctly.
     *
     * <p><b>Exactly one catch-up run, not a backlog.</b> The missed occurrence runs, and the
     * schedule then jumps to the next occurrence after now — the older missed occurrences are
     * discarded just as SKIP would discard them. The two policies differ only in whether that
     * single catch-up run happens at all.
     */
    FIRE_NOW,

    /**
     * Abandon the late occurrence and jump the schedule forward to the next future one.
     *
     * <p>For jobs where a stale run is worse than no run — "post the 09:00 market open summary"
     * is wrong if it goes out at 14:00. Note that only occurrences past the threshold are
     * skipped; a job running a few seconds behind still fires normally.
     */
    SKIP
}
