package com.chronos.scheduler;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import com.chronos.deadletter.DeadLetterRepository;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.job.HttpMethodType;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import com.chronos.job.MisfirePolicy;
import com.chronos.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reaper: what happens to executions whose dispatching node never came back.
 *
 * <p>Nothing here starts a real dispatch. The interesting state — a row that is RUNNING with no
 * live thread behind it — is precisely the state a crash leaves, so the tests write it directly.
 * That is the honest simulation: from the database's point of view, a node that died and a row
 * inserted this way are indistinguishable, which is exactly why the reaper has to work from the
 * row alone.
 *
 * <p>The test profile sets {@code reap.grace-sec: 0}, so the cutoff is just the job's own
 * {@code timeout_sec} and a fixture with a 1-second timeout is reapable almost immediately.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReaperIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final DeadLetterRepository deadLetters;
    private final UserRepository users;
    private final ExecutionReaper reaper;
    private final ExecutionEnqueuer enqueuer;
    private final ExecutionCompletionService completion;

    @Autowired
    ReaperIT(JobRepository jobs, JobExecutionRepository executions, DeadLetterRepository deadLetters,
             UserRepository users, ExecutionReaper reaper, ExecutionEnqueuer enqueuer,
             ExecutionCompletionService completion) {
        this.jobs = jobs;
        this.executions = executions;
        this.deadLetters = deadLetters;
        this.users = users;
        this.reaper = reaper;
        this.enqueuer = enqueuer;
        this.completion = completion;
    }

    @BeforeEach
    void setUp() {
        deadLetters.deleteAllInBatch();
        executions.deleteAllInBatch();
        jobs.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private User newUser() {
        return users.saveAndFlush(User.builder()
                .email("reap" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("irrelevant")
                .role(Role.USER)
                .build());
    }

    /**
     * @param timeoutSec the reaper's cutoff, since the test profile sets the grace period to 0.
     */
    private Job newJob(int maxAttempts, int timeoutSec) {
        return jobs.saveAndFlush(Job.builder()
                .name("job-" + SEQ.incrementAndGet())
                .owner(newUser())
                .targetUrl("http://127.0.0.1:1/never-reached")
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                // Yearly: the enqueue sweep is global, so a minutely fixture from one test could
                // come due during another and skew its counts.
                .cronExpr("0 0 1 1 *")
                .timezone("UTC")
                .nextRunAt(null)
                .status(JobStatus.PAUSED)
                .maxAttempts(maxAttempts)
                .initialBackoffSec(1)
                .backoffMultiplier(new BigDecimal("1.00"))
                .timeoutSec(timeoutSec)
                .misfirePolicy(MisfirePolicy.FIRE_NOW)
                .build());
    }

    /**
     * A row in exactly the state a crashed node leaves behind.
     *
     * @param startedSecondsAgo negative means "never started" — the node died between committing
     *        the claim and stamping started_at, so only claimed_at is set.
     */
    private JobExecution runningSince(Job job, int attemptNo, long claimedSecondsAgo,
                                      long startedSecondsAgo) {
        Instant now = nowMicros();
        return executions.saveAndFlush(JobExecution.builder()
                .job(job)
                .attemptNo(attemptNo)
                .scheduledFor(now.minusSeconds(claimedSecondsAgo + 1))
                .runAt(now.minusSeconds(claimedSecondsAgo + 1))
                .claimedAt(now.minusSeconds(claimedSecondsAgo))
                .startedAt(startedSecondsAgo < 0 ? null : now.minusSeconds(startedSecondsAgo))
                .status(ExecutionStatus.RUNNING)
                .build());
    }

    private List<JobExecution> attemptsOf(Job job) {
        return executions.findByJobIdOrderByScheduledForDesc(job.getId(), PageRequest.of(0, 20))
                .getContent().stream()
                .sorted(Comparator.comparingInt(JobExecution::getAttemptNo))
                .toList();
    }

    private JobExecution reload(Long id) {
        return executions.findById(id).orElseThrow();
    }

    // ---------------------------------------------------------------- reaping

    @Test
    @DisplayName("an execution running past its timeout is failed and retried")
    void stuckExecutionIsReapedAndRetried() {
        Job job = newJob(3, 1);
        JobExecution stuck = runningSince(job, 1, 30, 30);

        assertThat(reaper.reapOnce()).isEqualTo(1);

        JobExecution reaped = reload(stuck.getId());
        assertThat(reaped.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(reaped.getFinishedAt()).isNotNull();
        assertThat(reaped.getResponseCode()).isNull(); // there was never a response
        assertThat(reaped.getErrorMessage()).contains("Reaped").contains("no response");

        // The reaper reuses M5's completion logic wholesale, so a retryable failure with
        // attempts left produces the next attempt exactly as a real 500 would.
        List<JobExecution> attempts = attemptsOf(job);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(1).getAttemptNo()).isEqualTo(2);
        assertThat(attempts.get(1).getStatus()).isEqualTo(ExecutionStatus.RETRY_SCHEDULED);
    }

    @Test
    @DisplayName("a row claimed but never dispatched is reaped, and says so")
    void claimedButNeverStartedIsReaped() {
        Job job = newJob(3, 1);
        JobExecution stuck = runningSince(job, 1, 30, -1); // started_at null

        assertThat(reaper.reapOnce()).isEqualTo(1);

        JobExecution reaped = reload(stuck.getId());
        assertThat(reaped.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        // The distinction matters to whoever reads this: the target was never contacted, so
        // there is no point debugging the target.
        assertThat(reaped.getErrorMessage()).contains("never dispatched");
    }

    @Test
    @DisplayName("an execution still within its timeout is left alone")
    void healthyExecutionIsNotReaped() {
        Job job = newJob(3, 300);
        JobExecution live = runningSince(job, 1, 5, 5);

        assertThat(reaper.reapOnce()).isZero();
        assertThat(reload(live.getId()).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("the timeout is per job — one job's long timeout does not protect another's")
    void timeoutIsPerJob() {
        Job patient = newJob(3, 300); // 300 is the ceiling ck_jobs_timeout allows
        Job impatient = newJob(3, 1);
        JobExecution slowButAllowed = runningSince(patient, 1, 60, 60);
        JobExecution overdue = runningSince(impatient, 1, 60, 60);

        assertThat(reaper.reapOnce()).isEqualTo(1);

        assertThat(reload(slowButAllowed.getId()).getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(reload(overdue.getId()).getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("reaping the final attempt dead-letters it")
    void reapingLastAttemptDeadLetters() {
        Job job = newJob(1, 1); // one attempt only, so there is no retry to schedule
        JobExecution stuck = runningSince(job, 1, 30, 30);

        reaper.reapOnce();

        assertThat(reload(stuck.getId()).getStatus()).isEqualTo(ExecutionStatus.DEAD);
        assertThat(deadLetters.findByExecutionId(stuck.getId())).isPresent()
                .get()
                .satisfies(dl -> assertThat(dl.getReason()).contains("Reaped"));
    }

    @Test
    @DisplayName("a slow dispatch that returns after being reaped cannot overwrite the outcome")
    void lateOutcomeIsDiscarded() {
        Job job = newJob(1, 1);
        JobExecution stuck = runningSince(job, 1, 30, 30);

        reaper.reapOnce();
        assertThat(reload(stuck.getId()).getStatus()).isEqualTo(ExecutionStatus.DEAD);

        /*
         * The race the reaper cannot avoid: the node was not dead, only slow, and its dispatch
         * thread now comes back holding a 200. Without the RUNNING guard in recordOutcome this
         * would flip a DEAD execution to SUCCEEDED while its dead letter sat in the queue —
         * an occurrence recorded as both dead and successful.
         */
        completion.recordOutcome(stuck.getId(), DispatchOutcome.success(200, "too late"));

        JobExecution after = reload(stuck.getId());
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.DEAD);
        assertThat(after.getResponseCode()).isNull();
        assertThat(deadLetters.findByExecutionId(stuck.getId())).isPresent();
    }

    @Test
    @DisplayName("reaping unblocks a job the stuck row had frozen out of the schedule")
    void reapingUnblocksTheSchedule() {
        // maxAttempts 1, so reaping is terminal and leaves nothing unfinished behind.
        Job job = newJob(1, 1);
        JobExecution stuck = runningSince(job, 1, 30, 30);

        // Make the job schedulable again: due now, but with the abandoned row still RUNNING.
        job.setStatus(JobStatus.ENABLED);
        job.setNextRunAt(nowMicros().minusSeconds(30));
        jobs.saveAndFlush(job);

        // The overlap guard sees an unfinished attempt and refuses. This is the failure mode the
        // reaper exists for: without it the job is frozen out of its own schedule permanently.
        assertThat(enqueuer.enqueueDueJobs()).isZero();

        reaper.reapOnce();
        assertThat(reload(stuck.getId()).getStatus()).isEqualTo(ExecutionStatus.DEAD);

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);
    }

    @Test
    @DisplayName("reaping is idempotent — a second sweep finds nothing left")
    void secondSweepIsANoOp() {
        Job job = newJob(3, 1);
        runningSince(job, 1, 30, 30);

        assertThat(reaper.reapOnce()).isEqualTo(1);
        // The retry row created by the first sweep is RETRY_SCHEDULED, not RUNNING, so it is
        // not a reap candidate.
        assertThat(reaper.reapOnce()).isZero();
    }
}
