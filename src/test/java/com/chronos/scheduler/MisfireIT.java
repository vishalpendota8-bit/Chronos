package com.chronos.scheduler;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import com.chronos.config.SchedulerProperties;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to an occurrence nobody got round to on time.
 *
 * <p>The scenario every test here sets up is "the cluster was down": a job whose
 * {@code next_run_at} is hours in the past, discovered by the first sweep after the lights came
 * back on. All the fixtures pin occurrences to exact minute boundaries so the recomputed
 * {@code next_run_at} can be asserted precisely rather than approximately.
 */
@SpringBootTest
@ActiveProfiles("test")
class MisfireIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final UserRepository users;
    private final ExecutionEnqueuer enqueuer;
    private final SchedulerProperties properties;

    @Autowired
    MisfireIT(JobRepository jobs, JobExecutionRepository executions, UserRepository users,
              ExecutionEnqueuer enqueuer, SchedulerProperties properties) {
        this.jobs = jobs;
        this.executions = executions;
        this.users = users;
        this.enqueuer = enqueuer;
        this.properties = properties;
    }

    @BeforeEach
    void setUp() {
        executions.deleteAllInBatch();
        jobs.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- fixtures

    /** Minute-aligned, because the fixtures run on "* * * * *" and the assertions are exact. */
    private static Instant thisMinute() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES);
    }

    private User newUser() {
        return users.saveAndFlush(User.builder()
                .email("misfire" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("irrelevant")
                .role(Role.USER)
                .build());
    }

    private Job newJob(MisfirePolicy policy, Instant nextRunAt) {
        return jobs.saveAndFlush(Job.builder()
                .name("job-" + SEQ.incrementAndGet())
                .owner(newUser())
                .targetUrl("http://127.0.0.1:1/never-dispatched")
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                .cronExpr("* * * * *")
                .timezone("UTC")
                .nextRunAt(nextRunAt)
                .status(JobStatus.ENABLED)
                .maxAttempts(3)
                .initialBackoffSec(10)
                .backoffMultiplier(new BigDecimal("2.00"))
                .timeoutSec(30)
                .misfirePolicy(policy)
                .build());
    }

    private List<JobExecution> executionsOf(Job job) {
        return executions.findByJobIdOrderByScheduledForDesc(job.getId(), PageRequest.of(0, 20))
                .getContent();
    }

    private Instant nextRunOf(Job job) {
        return jobs.findById(job.getId()).orElseThrow().getNextRunAt();
    }

    // ---------------------------------------------------------------- not a misfire

    @Test
    @DisplayName("a slightly late occurrence is not a misfire: it fires and the grid is preserved")
    void ordinaryLatenessIsNotAMisfire() {
        // One minute late, far inside the 300s threshold — this is what every healthy sweep
        // looks like, and it must not be treated as an exception.
        Instant occurrence = thisMinute().minusSeconds(60);
        Job job = newJob(MisfirePolicy.SKIP, occurrence);

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);

        assertThat(executionsOf(job)).hasSize(1)
                .allSatisfy(e -> assertThat(e.getScheduledFor()).isEqualTo(occurrence));

        // Chained off the occurrence, not off now: this is the anti-drift rule, and it is why a
        // pointer can legitimately still be in the past after a sweep.
        assertThat(nextRunOf(job)).isEqualTo(occurrence.plusSeconds(60));
    }

    // ---------------------------------------------------------------- FIRE_NOW

    @Test
    @DisplayName("FIRE_NOW runs the missed occurrence once, then rejoins the present")
    void fireNowRunsOnceAndCatchesUp() {
        Instant missed = thisMinute().minusSeconds(3600);
        Job job = newJob(MisfirePolicy.FIRE_NOW, missed);

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);

        // The missed occurrence really does run, stamped with the time it was meant to happen.
        assertThat(executionsOf(job)).hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.getScheduledFor()).isEqualTo(missed);
                    assertThat(e.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
                });

        /*
         * The half of FIRE_NOW that is easy to get wrong. The normal rule would chain the pointer
         * to missed + 1 minute — still 59 minutes in the past — and the job would then replay the
         * whole hour one occurrence at a time. Jumping past now is what makes it exactly one
         * catch-up run.
         */
        assertThat(nextRunOf(job)).isAfter(Instant.now());
    }

    @Test
    @DisplayName("FIRE_NOW does not replay the backlog: the second sweep enqueues nothing")
    void fireNowDoesNotReplayTheBacklog() {
        Job job = newJob(MisfirePolicy.FIRE_NOW, thisMinute().minusSeconds(3600));

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);
        // Nothing left that is due — had the pointer stayed in the past, this would be another
        // run, and the next sweep another, for all 60 missed minutes.
        assertThat(enqueuer.enqueueDueJobs()).isZero();
        assertThat(executionsOf(job)).hasSize(1);
    }

    // ---------------------------------------------------------------- SKIP

    @Test
    @DisplayName("SKIP abandons the missed occurrence entirely and rejoins the schedule")
    void skipDropsTheMissedOccurrence() {
        Job job = newJob(MisfirePolicy.SKIP, thisMinute().minusSeconds(3600));

        assertThat(enqueuer.enqueueDueJobs()).isZero();

        // Nothing ran. That is the whole point: a stale run would have been worse than no run.
        assertThat(executionsOf(job)).isEmpty();

        // But the job is not stalled — it is back on its schedule, one occurrence from now.
        Instant next = nextRunOf(job);
        assertThat(next).isAfter(Instant.now());
        assertThat(next).isBefore(Instant.now().plusSeconds(61));
    }

    @Test
    @DisplayName("SKIP collapses a whole backlog in one sweep, not one occurrence per sweep")
    void skipCollapsesTheBacklogInOneStep() {
        // A day of missed minutely occurrences: 1,440 of them.
        Job job = newJob(MisfirePolicy.SKIP, thisMinute().minus(24, ChronoUnit.HOURS));

        enqueuer.enqueueDueJobs();

        // Recomputed from now, not stepped one occurrence at a time — otherwise catching up
        // would itself take 1,440 sweeps.
        assertThat(nextRunOf(job)).isAfter(Instant.now());
        assertThat(enqueuer.enqueueDueJobs()).isZero();
    }

    // ---------------------------------------------------------------- the threshold itself

    @Test
    @DisplayName("the threshold is what separates the two behaviours, and it is configured")
    void thresholdSeparatesTheTwoPaths() {
        // Sanity-check the assumption the tests above are built on, so that retuning the
        // threshold in application.yml fails here rather than silently voiding them.
        assertThat(properties.misfireThresholdSec()).isEqualTo(300);

        Job justInside = newJob(MisfirePolicy.SKIP, thisMinute().minusSeconds(240));
        Job justOutside = newJob(MisfirePolicy.SKIP, thisMinute().minusSeconds(360));

        enqueuer.enqueueDueJobs();

        assertThat(executionsOf(justInside)).hasSize(1);
        assertThat(executionsOf(justOutside)).isEmpty();
    }
}
