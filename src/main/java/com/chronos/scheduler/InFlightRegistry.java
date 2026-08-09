package com.chronos.scheduler;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this node is doing right now, and whether it is still willing to take on more.
 *
 * <p>Every other piece of state in Chronos lives in Postgres, deliberately — that is what lets
 * any node do any work. This one is per-node and in memory, because it answers a question only
 * this JVM can answer: <em>which executions are being dispatched by threads inside this
 * process</em>. The database knows a row is RUNNING; it does not know which node owns it, and
 * adding a {@code claimed_by} column would mean writing a node identity on the hot path to
 * support one operation (shutdown) that already knows the answer for free.
 *
 * <p>Used by two collaborators:
 * <ul>
 *   <li>{@link ExecutionPoller} registers each execution before submitting it and clears it when
 *       the worker finishes;</li>
 *   <li>{@link SchedulerLifecycle} flips {@code accepting} to false on shutdown, waits for the
 *       set to drain, and hands back whatever is still in it.</li>
 * </ul>
 *
 * <p><b>Why {@code volatile} on the flag.</b> It is written by the shutdown thread and read by
 * the scheduler threads. Without {@code volatile} there is no happens-before edge between them,
 * and the JVM is entirely within its rights to let a polling thread keep reading a cached
 * {@code true} indefinitely — a shutdown that never takes effect. It is the cheapest correct
 * tool here: the flag is only ever set one way, so nothing needs an atomic read-modify-write.
 */
@Component
public class InFlightRegistry {

    /**
     * A concurrent set — the poller thread adds while dispatch threads remove. A plain HashSet
     * would corrupt under that, and synchronising every access would put a shared lock on the
     * dispatch path for no benefit.
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean accepting = true;

    /** False once shutdown has begun: the pollers stop claiming new work immediately. */
    public boolean isAccepting() {
        return accepting;
    }

    /** Called by {@link SchedulerLifecycle} when the JVM is asked to stop. */
    public void stopAccepting() {
        this.accepting = false;
    }

    /**
     * Called when the lifecycle starts.
     *
     * <p>Normally that is once, at boot, and this is a no-op on a fresh flag. It exists because
     * {@link org.springframework.context.Lifecycle} is genuinely restartable — a context can be
     * stopped and started again — and a node that was told to start must go back to claiming
     * work. Without it, a restart would leave a silently idle scheduler.
     */
    public void startAccepting() {
        this.accepting = true;
    }

    /**
     * Called <em>before</em> the task is submitted to the pool, never after.
     *
     * <p>Ordering matters: registering after {@code execute()} would leave a window in which the
     * worker is already running an execution that shutdown cannot see, so a shutdown landing in
     * that window would consider the node drained and move on.
     */
    public void register(Long executionId) {
        inFlight.add(executionId);
    }

    public void complete(Long executionId) {
        inFlight.remove(executionId);
    }

    public int size() {
        return inFlight.size();
    }

    /** A snapshot for shutdown to release. Copied, because the live set is still being mutated. */
    public Set<Long> snapshot() {
        return Set.copyOf(inFlight);
    }
}
