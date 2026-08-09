package com.chronos.scheduler;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.HttpMethodType;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import com.chronos.job.MisfirePolicy;
import com.chronos.support.PostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graceful shutdown: what a node hands back on its way out.
 *
 * <p>The context is not actually torn down here — {@link SchedulerLifecycle#stop()} is called
 * directly. That is the whole of what Spring does to this bean on SIGTERM, so calling it by hand
 * exercises the real code path while leaving the shared container and application context intact
 * for the rest of the suite. {@code start()} is called again afterwards, which is exactly what
 * {@link org.springframework.context.Lifecycle} restart means and restores the node to accepting.
 *
 * <p>The test profile sets {@code shutdown-drain-sec: 1}, so an execution that never completes
 * costs one second rather than the production thirty-five.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShutdownIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final UserRepository users;
    private final SchedulerLifecycle lifecycle;
    private final InFlightRegistry registry;
    private final ExecutionPoller poller;
    private final ExecutionEnqueuer enqueuer;

    @Autowired
    ShutdownIT(JobRepository jobs, JobExecutionRepository executions, UserRepository users,
               SchedulerLifecycle lifecycle, InFlightRegistry registry, ExecutionPoller poller,
               ExecutionEnqueuer enqueuer) {
        this.jobs = jobs;
        this.executions = executions;
        this.users = users;
        this.lifecycle = lifecycle;
        this.registry = registry;
        this.poller = poller;
        this.enqueuer = enqueuer;
    }

    @BeforeEach
    void setUp() {
        executions.deleteAllInBatch();
        jobs.deleteAllInBatch();
        lifecycle.start(); // known-good starting state whatever a previous test did
    }

    @AfterEach
    void restart() {
        // Leaving the shared singleton registry in "not accepting" would silently disable the
        // poller for every IT class that reuses this cached context.
        lifecycle.start();
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Job newJob() {
        User owner = users.saveAndFlush(User.builder()
                .email("shutdown" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("irrelevant")
                .role(Role.USER)
                .build());

        return jobs.saveAndFlush(Job.builder()
                .name("job-" + SEQ.incrementAndGet())
                .owner(owner)
                .targetUrl("http://127.0.0.1:1/never-dispatched")
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                .cronExpr("0 0 1 1 *")
                .timezone("UTC")
                .nextRunAt(nowMicros().minusSeconds(30))
                .status(JobStatus.ENABLED)
                .maxAttempts(3)
                .initialBackoffSec(10)
                .backoffMultiplier(new BigDecimal("2.00"))
                .timeoutSec(30)
                .misfirePolicy(MisfirePolicy.FIRE_NOW)
                .build());
    }

    private JobExecution running(Job job, int attemptNo) {
        Instant now = nowMicros();
        return executions.saveAndFlush(JobExecution.builder()
                .job(job)
                .attemptNo(attemptNo)
                .scheduledFor(now.minusSeconds(attemptNo))
                .runAt(now.minusSeconds(attemptNo))
                .claimedAt(now)
                .startedAt(now)
                .status(ExecutionStatus.RUNNING)
                .build());
    }

    private ExecutionStatus statusOf(Long executionId) {
        return executions.findById(executionId).orElseThrow().getStatus();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("shutdown stops the node claiming any more work")
    void shutdownStopsClaiming() {
        Job job = newJob();
        enqueuer.enqueueDueJobs();
        assertThat(executions.count()).isEqualTo(1);

        lifecycle.stop();

        // The row is still QUEUED and perfectly claimable — this node has simply stopped asking,
        // which is what lets a peer take it instead.
        assertThat(poller.pollOnce()).isZero();
        assertThat(executions.findByJobIdOrderByScheduledForDesc(job.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5)).getContent())
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(ExecutionStatus.QUEUED));
    }

    @Test
    @DisplayName("shutdown stops the enqueue sweep too")
    void shutdownStopsEnqueueing() {
        newJob();

        lifecycle.stop();

        assertThat(enqueuer.enqueueDueJobs()).isZero();
        assertThat(executions.count()).isZero();
    }

    @Test
    @DisplayName("a dispatch that does not finish in time is handed back to the queue")
    void undrainedDispatchIsReleased() {
        Job job = newJob();
        JobExecution stuck = running(job, 1);

        // Stand in for a dispatch thread that is still waiting on a slow target: the row is
        // RUNNING and this node has it registered, but nothing is going to complete it.
        registry.register(stuck.getId());

        lifecycle.stop();

        /*
         * Released rather than left RUNNING. That is the whole point of the phase: a RUNNING row
         * is invisible to every poller on every node, so leaving it would strand the work until
         * the reaper noticed — timeout_sec plus grace later, with the job blocked by the overlap
         * guard the entire time. Back on QUEUED, a peer takes it on its very next tick.
         */
        assertThat(statusOf(stuck.getId())).isEqualTo(ExecutionStatus.QUEUED);
    }

    @Test
    @DisplayName("a retry attempt is released back to RETRY_SCHEDULED, not to QUEUED")
    void releasedRetryKeepsItsIdentity() {
        Job job = newJob();
        JobExecution retry = running(job, 2); // attempt 2 — this row is a retry

        registry.register(retry.getId());
        lifecycle.stop();

        // Attempt number is untouched, so this is still the same attempt, merely not started.
        // Returning it to QUEUED would misreport a retry as a job's first try.
        assertThat(statusOf(retry.getId())).isEqualTo(ExecutionStatus.RETRY_SCHEDULED);
        assertThat(executions.findById(retry.getId()).orElseThrow().getAttemptNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("a dispatch that finishes during the drain is left exactly as it recorded itself")
    void drainedDispatchIsNotClobbered() {
        Job job = newJob();
        JobExecution finished = running(job, 1);

        registry.register(finished.getId());

        // The dispatch completes and writes its own result, just as it would in the drain window.
        finished.setStatus(ExecutionStatus.SUCCEEDED);
        finished.setFinishedAt(Instant.now());
        executions.saveAndFlush(finished);
        registry.complete(finished.getId());

        lifecycle.stop();

        // Nothing to release, and release() is a no-op on a non-RUNNING row anyway — so a result
        // that arrived just before the deadline is never overwritten by the shutdown path.
        assertThat(statusOf(finished.getId())).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("restarting the lifecycle puts the node back to work")
    void restartResumesClaiming() {
        Job job = newJob();
        enqueuer.enqueueDueJobs();

        lifecycle.stop();
        assertThat(poller.pollOnce()).isZero();

        lifecycle.start();

        // Lifecycle is restartable, and a node told to start again must resume claiming rather
        // than stay silently idle.
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(executions.findByJobIdOrderByScheduledForDesc(job.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5)).getContent())
                .isNotEmpty();
    }
}
