package com.chronos.stats;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import com.chronos.common.ConflictException;
import com.chronos.deadletter.DeadLetter;
import com.chronos.deadletter.DeadLetterRepository;
import com.chronos.execution.ExecutionStatus;
import com.chronos.execution.JobExecution;
import com.chronos.execution.JobExecutionRepository;
import com.chronos.execution.dto.ExecutionResponse;
import com.chronos.job.HttpMethodType;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import com.chronos.job.JobTriggerService;
import com.chronos.job.MisfirePolicy;
import com.chronos.security.ChronosUserDetails;
import com.chronos.stats.dto.JobStatsResponse;
import com.chronos.stats.dto.StatsResponse;
import com.chronos.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manual triggering and the statistics endpoints.
 *
 * <p>Grouped into one class because they are the two halves of the same operator story: press
 * "run now", then look at the numbers to see what it did.
 *
 * <p>Executions here are written directly in whatever terminal state each assertion needs. These
 * tests are about counting and about the rules around creating a row — not about dispatch, which
 * {@code SchedulerIT} and {@code RetryIT} already cover end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TriggerAndStatsIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MockMvc mvc;
    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final DeadLetterRepository deadLetters;
    private final UserRepository users;
    private final JobTriggerService triggerService;
    private final StatsService statsService;

    private User owner;
    private ChronosUserDetails caller;

    @Autowired
    TriggerAndStatsIT(MockMvc mvc, JobRepository jobs, JobExecutionRepository executions,
                      DeadLetterRepository deadLetters, UserRepository users,
                      JobTriggerService triggerService, StatsService statsService) {
        this.mvc = mvc;
        this.jobs = jobs;
        this.executions = executions;
        this.deadLetters = deadLetters;
        this.users = users;
        this.triggerService = triggerService;
        this.statsService = statsService;
    }

    @BeforeEach
    void setUp() {
        // A clean slate matters more here than anywhere else: these assertions are exact counts,
        // and the container is shared with every other IT class.
        deadLetters.deleteAllInBatch();
        executions.deleteAllInBatch();
        jobs.deleteAllInBatch();

        owner = newUser(Role.USER);
        caller = new ChronosUserDetails(owner.getId(), owner.getEmail(), Role.USER, null);
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private User newUser(Role role) {
        return users.saveAndFlush(User.builder()
                .email("stats" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("irrelevant")
                .role(role)
                .build());
    }

    private Job newJob(User jobOwner, JobStatus status) {
        return jobs.saveAndFlush(Job.builder()
                .name("job-" + SEQ.incrementAndGet())
                .owner(jobOwner)
                .targetUrl("http://127.0.0.1:1/never-dispatched")
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                .cronExpr("0 0 1 1 *")
                .timezone("UTC")
                .nextRunAt(status == JobStatus.ENABLED ? nowMicros().plusSeconds(3600) : null)
                .status(status)
                .maxAttempts(3)
                .initialBackoffSec(10)
                .backoffMultiplier(new BigDecimal("2.00"))
                .timeoutSec(30)
                .misfirePolicy(MisfirePolicy.FIRE_NOW)
                .build());
    }

    /** An attempt already in its final state, offset back from now so windows can be tested. */
    private JobExecution execution(Job job, int attemptNo, ExecutionStatus status,
                                   long secondsAgo, Long durationMs) {
        Instant scheduledFor = nowMicros().minusSeconds(secondsAgo);
        Instant started = durationMs == null ? null : scheduledFor;
        Instant finished = durationMs == null ? null : scheduledFor.plusMillis(durationMs);

        return executions.saveAndFlush(JobExecution.builder()
                .job(job)
                .attemptNo(attemptNo)
                .scheduledFor(scheduledFor)
                .runAt(scheduledFor)
                .claimedAt(started)
                .startedAt(started)
                .finishedAt(finished)
                .status(status)
                .build());
    }

    // ---------------------------------------------------------------- trigger

    @Test
    @DisplayName("triggering queues an immediate execution without touching the schedule")
    void triggerQueuesAnExecutionAndLeavesTheScheduleAlone() {
        Job job = newJob(owner, JobStatus.ENABLED);
        Instant scheduleBefore = job.getNextRunAt();

        ExecutionResponse queued = triggerService.trigger(job.getId(), caller);

        assertThat(queued.status()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(queued.attemptNo()).isEqualTo(1);
        // Eligible immediately — a manual run does not wait for anything.
        assertThat(queued.runAt()).isBeforeOrEqualTo(Instant.now());

        // The whole point: pressing "run now" at 14:32 must not move the 02:00 nightly run.
        assertThat(jobs.findById(job.getId()).orElseThrow().getNextRunAt())
                .isEqualTo(scheduleBefore);
    }

    @Test
    @DisplayName("a triggered run is a new occurrence, not another attempt at an existing one")
    void triggerCreatesItsOwnOccurrence() {
        Job job = newJob(owner, JobStatus.ENABLED);
        JobExecution scheduled = execution(job, 1, ExecutionStatus.SUCCEEDED, 600, 120L);

        ExecutionResponse triggered = triggerService.trigger(job.getId(), caller);

        // A retry would share scheduledFor and bump attemptNo; a trigger does neither.
        assertThat(triggered.scheduledFor()).isNotEqualTo(scheduled.getScheduledFor());
        assertThat(triggered.attemptNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("a paused job can be triggered, an archived one cannot")
    void pausedIsTriggerableArchivedIsNot() {
        // Pause suspends the schedule, not the operator. Triggering a paused job by hand is how
        // you test a fix without letting cron loose.
        Job paused = newJob(owner, JobStatus.PAUSED);
        assertThat(triggerService.trigger(paused.getId(), caller)).isNotNull();

        Job archived = newJob(owner, JobStatus.ARCHIVED);
        assertThatThrownBy(() -> triggerService.trigger(archived.getId(), caller))
                .hasMessageContaining("archived");
    }

    @Test
    @DisplayName("triggering a job that already has work in flight is a 409, not a second run")
    void triggerRespectsTheOverlapRule() {
        Job job = newJob(owner, JobStatus.ENABLED);
        execution(job, 1, ExecutionStatus.RUNNING, 5, null);

        assertThatThrownBy(() -> triggerService.trigger(job.getId(), caller))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already running");
    }

    @Test
    @DisplayName("a user cannot trigger someone else's job, and is told it does not exist")
    void triggerIsOwnershipScoped() {
        Job someoneElses = newJob(newUser(Role.USER), JobStatus.ENABLED);

        // 404 rather than 403 — confirming the id exists would leak other tenants' job ids.
        assertThatThrownBy(() -> triggerService.trigger(someoneElses.getId(), caller))
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("POST /jobs/{id}/trigger answers 202 with a Location for the queued execution")
    void triggerRoute() throws Exception {
        Job job = newJob(owner, JobStatus.ENABLED);

        mvc.perform(post("/jobs/{id}/trigger", job.getId()).with(user(caller)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.jobId").value(job.getId()));
    }

    // ---------------------------------------------------------------- system stats

    @Test
    @DisplayName("the overview counts jobs by status and executions within the window")
    void overviewCounts() {
        Job enabled = newJob(owner, JobStatus.ENABLED);
        newJob(owner, JobStatus.PAUSED);
        newJob(owner, JobStatus.ARCHIVED);

        execution(enabled, 1, ExecutionStatus.SUCCEEDED, 60, 100L);
        execution(enabled, 2, ExecutionStatus.FAILED, 120, 100L);
        execution(enabled, 3, ExecutionStatus.DEAD, 180, 100L);
        execution(enabled, 4, ExecutionStatus.QUEUED, 240, null);

        StatsResponse stats = statsService.overview(caller, 24);

        assertThat(stats.totalJobs()).isEqualTo(3);
        assertThat(stats.enabledJobs()).isEqualTo(1);
        assertThat(stats.pausedJobs()).isEqualTo(1);
        assertThat(stats.archivedJobs()).isEqualTo(1);

        assertThat(stats.succeeded()).isEqualTo(1);
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.dead()).isEqualTo(1);
        assertThat(stats.pending()).isEqualTo(1);
        assertThat(stats.executionsInWindow()).isEqualTo(4);

        // 1 succeeded / (1 succeeded + 1 dead). The FAILED attempt is excluded from both sides:
        // an occurrence that failed once and then succeeded is a success, not a half-failure.
        assertThat(stats.successRate()).isEqualTo(0.5);

        assertThat(stats.nextRunAt()).isEqualTo(enabled.getNextRunAt());
    }

    @Test
    @DisplayName("executions outside the window are excluded")
    void windowExcludesOlderExecutions() {
        Job job = newJob(owner, JobStatus.ENABLED);
        execution(job, 1, ExecutionStatus.SUCCEEDED, 60, 100L);           // a minute ago
        execution(job, 2, ExecutionStatus.SUCCEEDED, 60 * 60 * 48, 100L); // two days ago

        assertThat(statsService.overview(caller, 24).succeeded()).isEqualTo(1);
        assertThat(statsService.overview(caller, 24 * 7).succeeded()).isEqualTo(2);
    }

    @Test
    @DisplayName("a success rate of null, not zero, when nothing has run")
    void successRateIsNullWithNoData() {
        newJob(owner, JobStatus.ENABLED);

        // A red "0%" for a system that has simply not run yet would be a lie.
        assertThat(statsService.overview(caller, 24).successRate()).isNull();
    }

    @Test
    @DisplayName("a USER sees only their own numbers; an ADMIN sees everything")
    void statsAreOwnershipScoped() {
        Job mine = newJob(owner, JobStatus.ENABLED);
        Job theirs = newJob(newUser(Role.USER), JobStatus.ENABLED);
        execution(mine, 1, ExecutionStatus.SUCCEEDED, 60, 100L);
        execution(theirs, 1, ExecutionStatus.SUCCEEDED, 60, 100L);

        assertThat(statsService.overview(caller, 24).totalJobs()).isEqualTo(1);
        assertThat(statsService.overview(caller, 24).succeeded()).isEqualTo(1);

        User admin = newUser(Role.ADMIN);
        ChronosUserDetails adminCaller =
                new ChronosUserDetails(admin.getId(), admin.getEmail(), Role.ADMIN, null);

        assertThat(statsService.overview(adminCaller, 24).totalJobs()).isEqualTo(2);
        assertThat(statsService.overview(adminCaller, 24).succeeded()).isEqualTo(2);
    }

    @Test
    @DisplayName("pending dead letters are counted, and stop being pending once replayed")
    void pendingDeadLettersAreCounted() {
        Job job = newJob(owner, JobStatus.ENABLED);
        JobExecution deadOne = execution(job, 1, ExecutionStatus.DEAD, 60, 100L);
        JobExecution deadTwo = execution(job, 2, ExecutionStatus.DEAD, 120, 100L);

        deadLetters.saveAndFlush(DeadLetter.builder()
                .execution(deadOne).job(job).reason("gave up").failedAt(nowMicros()).build());
        deadLetters.saveAndFlush(DeadLetter.builder()
                .execution(deadTwo).job(job).reason("gave up")
                .failedAt(nowMicros()).replayedAt(nowMicros()).build());

        // Only the unreplayed one is on the operator's to-do list.
        assertThat(statsService.overview(caller, 24).pendingDeadLetters()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- per-job stats

    @Test
    @DisplayName("per-job stats summarise all time, including average duration and last run")
    void jobStats() {
        Job job = newJob(owner, JobStatus.ENABLED);
        execution(job, 1, ExecutionStatus.SUCCEEDED, 300, 100L);
        execution(job, 2, ExecutionStatus.SUCCEEDED, 200, 300L);
        JobExecution last = execution(job, 3, ExecutionStatus.DEAD, 100, 200L);

        JobStatsResponse stats = statsService.forJob(job.getId(), caller);

        assertThat(stats.jobId()).isEqualTo(job.getId());
        assertThat(stats.jobName()).isEqualTo(job.getName());
        assertThat(stats.totalExecutions()).isEqualTo(3);
        assertThat(stats.succeeded()).isEqualTo(2);
        assertThat(stats.dead()).isEqualTo(1);
        assertThat(stats.successRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));

        // (0.1 + 0.3 + 0.2) / 3 seconds.
        assertThat(stats.averageDurationSec()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));

        assertThat(stats.lastRunAt()).isEqualTo(last.getFinishedAt());
        assertThat(stats.lastRunStatus()).isEqualTo(ExecutionStatus.DEAD);
    }

    @Test
    @DisplayName("a job that has never run reports nulls, not zeroes")
    void jobStatsWithNoHistory() {
        Job job = newJob(owner, JobStatus.ENABLED);

        JobStatsResponse stats = statsService.forJob(job.getId(), caller);

        assertThat(stats.totalExecutions()).isZero();
        assertThat(stats.successRate()).isNull();
        // Null, not 0.0 — 0.0 would claim the job always returns instantly.
        assertThat(stats.averageDurationSec()).isNull();
        assertThat(stats.lastRunAt()).isNull();
        assertThat(stats.lastRunStatus()).isNull();
    }

    @Test
    @DisplayName("per-job stats are ownership scoped")
    void jobStatsAreOwnershipScoped() {
        Job someoneElses = newJob(newUser(Role.USER), JobStatus.ENABLED);

        assertThatThrownBy(() -> statsService.forJob(someoneElses.getId(), caller))
                .hasMessageContaining("not found");
    }
}
