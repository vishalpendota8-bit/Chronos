package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Graceful shutdown for the execution engine.
 *
 * <p><b>What "graceful" has to mean for a scheduler,</b> which is more than it means for a web
 * server. A web server draining requests only has to avoid cutting off its own clients. A
 * scheduler node also holds a claim on shared state: every execution it has marked RUNNING is
 * invisible to every other node in the cluster. Exit carelessly and those rows are stranded until
 * {@link ExecutionReaper} notices them — up to a job's whole {@code timeout_sec} plus the grace
 * period later, during which the affected jobs are also blocked by the overlap guard. On a rolling
 * deploy that restarts every node in turn, that is a real and repeated stall.
 *
 * <p>So shutdown runs in three phases:
 *
 * <ol>
 *   <li><b>Stop claiming.</b> {@link InFlightRegistry#stopAccepting()} makes the pollers no-ops
 *       at once, so the node stops taking on work it will not be around to finish. This is the
 *       important step and it is instantaneous — everything after it is best-effort.</li>
 *   <li><b>Drain.</b> Wait up to {@code chronos.scheduler.shutdown-drain-sec} for the dispatches
 *       already in flight to finish and record their own results. Most will: they are bounded by
 *       {@code timeout_sec}.</li>
 *   <li><b>Hand back the rest.</b> Anything still in flight when the deadline passes is released
 *       to QUEUED, so a peer picks it up on its next tick instead of waiting for the reaper.</li>
 * </ol>
 *
 * <p><b>The tradeoff in phase 3, stated plainly:</b> a released row will be dispatched again by
 * another node while this node's request may still be on the wire, so the target can see the same
 * job twice. That is the same at-least-once bargain the reaper makes, and the same
 * {@code X-Idempotency-Key} covers it — but here we are choosing it deliberately, to trade a
 * possible duplicate for a guaranteed multi-minute stall. Raise {@code shutdown-drain-sec} above
 * your longest {@code timeout_sec} if you would rather wait than risk the duplicate.
 *
 * <p><b>New concept — {@link SmartLifecycle} rather than {@code @PreDestroy}.</b> A
 * {@code @PreDestroy} method runs during bean <em>destruction</em>, by which time the beans it
 * depends on may already be gone — releasing rows needs a live DataSource, and there is no
 * ordering guarantee that it still has one. {@code SmartLifecycle} runs in an earlier, explicitly
 * ordered stop phase while the whole context is intact. Phases stop in descending order, so
 * {@link #getPhase()} returning the maximum means this is the very first thing to stop: the node
 * stops claiming before anything it relies on begins to disappear.
 */
@Component
public class SchedulerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLifecycle.class);

    /** How often the drain loop re-checks. Short enough to exit promptly once the set empties. */
    private static final Duration DRAIN_POLL = Duration.ofMillis(100);

    private final InFlightRegistry registry;
    private final ExecutionClaimService claimService;
    private final SchedulerProperties properties;

    private volatile boolean running;

    public SchedulerLifecycle(InFlightRegistry registry, ExecutionClaimService claimService,
                              SchedulerProperties properties) {
        this.registry = registry;
        this.claimService = claimService;
        this.properties = properties;
    }

    @Override
    public void start() {
        registry.startAccepting();
        running = true;
    }

    /**
     * Spring only calls {@link #stop()} on a bean it believes is running, so this must reflect
     * {@link #start()} honestly or shutdown would be skipped entirely.
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** Highest phase stops first. See the class javadoc. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void stop() {
        running = false;

        registry.stopAccepting();
        int outstanding = registry.size();
        log.info("Shutdown: no longer claiming work; {} dispatch(es) in flight", outstanding);

        if (outstanding == 0) {
            return;
        }

        boolean drained = awaitDrain(Duration.ofSeconds(properties.shutdownDrainSec()));
        if (drained) {
            log.info("Shutdown: all in-flight dispatches completed");
            return;
        }

        releaseStragglers();
    }

    /**
     * Waits for the in-flight set to empty.
     *
     * @return true if it drained before the deadline.
     */
    private boolean awaitDrain(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);

        while (registry.size() > 0 && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(DRAIN_POLL.toMillis());
            } catch (InterruptedException e) {
                // Something wants this thread to stop waiting. Restore the flag rather than
                // swallowing it — a caller further up may be checking — and fall through to
                // releasing, which is the safe outcome for the rows either way.
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return registry.size() == 0;
    }

    /**
     * Hands back whatever never finished, so a peer can take it immediately.
     *
     * <p>Each release is its own transaction inside {@link ExecutionClaimService#release}, and
     * that method is a no-op for a row that is no longer RUNNING — so a dispatch that completed
     * in the gap between the snapshot and the release is not clobbered.
     */
    private void releaseStragglers() {
        Set<Long> stragglers = registry.snapshot();
        log.warn("Shutdown: {} dispatch(es) did not finish within {}s; releasing them back to the "
                        + "queue for another node",
                stragglers.size(), properties.shutdownDrainSec());

        for (Long executionId : stragglers) {
            try {
                claimService.release(executionId);
                // Handed off, so this node no longer owns it. Clearing keeps the registry an
                // accurate answer to "what is this node still doing" rather than a growing list
                // of things it used to be doing.
                registry.complete(executionId);
            } catch (RuntimeException e) {
                // Never let one failure abandon the rest — and a row we fail to release is not
                // lost, only slow: the reaper will find it.
                log.error("Shutdown: could not release execution {}", executionId, e);
            }
        }
    }
}
