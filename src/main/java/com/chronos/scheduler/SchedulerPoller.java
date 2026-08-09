package com.chronos.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The three timers that drive the engine.
 *
 * <p><b>Why {@code fixedDelay} and not {@code fixedRate}:</b> fixedDelay measures the gap from
 * the <em>end</em> of one run to the start of the next, so a slow tick simply delays the next
 * one. fixedRate measures start-to-start and queues up overlapping runs when a tick overruns —
 * with a database poller that means piling more pollers onto a database that is already the
 * reason things are slow.
 *
 * <p><b>Why this is safe to run on every node simultaneously:</b> nothing here coordinates with
 * other nodes. The enqueue sweep is made safe by the {@code (job_id, scheduled_for, attempt_no)}
 * unique index, and the claim and reap sweeps by {@code FOR UPDATE SKIP LOCKED}. All three are
 * properties of the database rather than of the application, which is what lets M7 run three
 * copies of this process and still see each occurrence execute exactly once.
 *
 * <p>Conditional on {@code chronos.scheduler.enabled} so integration tests can drive
 * {@link ExecutionEnqueuer}, {@link ExecutionPoller} and {@link ExecutionReaper} by hand rather
 * than racing these timers.
 */
@Component
@ConditionalOnProperty(name = "chronos.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerPoller {

    private static final Logger log = LoggerFactory.getLogger(SchedulerPoller.class);

    private final ExecutionEnqueuer enqueuer;
    private final ExecutionPoller poller;
    private final ExecutionReaper reaper;

    public SchedulerPoller(ExecutionEnqueuer enqueuer, ExecutionPoller poller,
                           ExecutionReaper reaper) {
        this.enqueuer = enqueuer;
        this.poller = poller;
        this.reaper = reaper;
    }

    /** Materialises due cron occurrences into execution rows. */
    @Scheduled(fixedDelayString = "${chronos.scheduler.enqueue-interval-ms}")
    public void enqueueTick() {
        try {
            enqueuer.enqueueDueJobs();
        } catch (Exception e) {
            // Catching here keeps one bad tick from looking like an outage: an uncaught
            // exception is logged by Spring and the schedule continues either way, but this
            // gives the failure a name.
            log.error("Enqueue tick failed", e);
        }
    }

    /** Claims due executions and hands them to the dispatch pool. */
    @Scheduled(fixedDelayString = "${chronos.scheduler.poll-interval-ms}")
    public void pollTick() {
        try {
            poller.pollOnce();
        } catch (Exception e) {
            log.error("Poll tick failed", e);
        }
    }

    /**
     * Frees executions stranded in RUNNING by a node that died mid-dispatch.
     *
     * <p>Runs far less often than the other two. A stranded row is already past its timeout, so
     * finding it a few seconds sooner buys nothing, and this sweep joins {@code jobs} —
     * cheap at these volumes, but there is no reason to pay for it on the poller's cadence.
     */
    @Scheduled(fixedDelayString = "${chronos.scheduler.reap.interval-ms}")
    public void reapTick() {
        try {
            reaper.reapOnce();
        } catch (Exception e) {
            log.error("Reap tick failed", e);
        }
    }
}
