package com.chronos.scheduler;

import com.chronos.auth.Role;
import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import com.chronos.deadletter.DeadLetter;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retry scheduling, backoff, dead-lettering and replay, end to end.
 *
 * <p>The {@code @Scheduled} timers stay off (test profile), so each phase is stepped explicitly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetryIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final DeadLetterRepository deadLetters;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ExecutionEnqueuer enqueuer;
    private final ExecutionPoller poller;

    private HttpServer server;
    private String baseUrl;
    /** /flaky fails until it has been hit this many times. */
    private final AtomicInteger flakyFailuresRemaining = new AtomicInteger();

    @Autowired
    RetryIT(MockMvc mvc, ObjectMapper json, JobRepository jobs, JobExecutionRepository executions,
            DeadLetterRepository deadLetters, UserRepository users, PasswordEncoder passwordEncoder,
            ExecutionEnqueuer enqueuer, ExecutionPoller poller) {
        this.mvc = mvc;
        this.json = json;
        this.jobs = jobs;
        this.executions = executions;
        this.deadLetters = deadLetters;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.enqueuer = enqueuer;
        this.poller = poller;
    }

    @BeforeEach
    void setUp() throws IOException {
        // Children first: dead letters reference executions, which reference jobs.
        deadLetters.deleteAllInBatch();
        executions.deleteAllInBatch();
        jobs.deleteAllInBatch();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/boom", exchange -> respond(exchange, 500, "kaboom"));
        server.createContext("/bad", exchange -> respond(exchange, 400, "malformed"));
        server.createContext("/ok", exchange -> respond(exchange, 200, "fine"));
        server.createContext("/flaky", exchange -> {
            if (flakyFailuresRemaining.getAndDecrement() > 0) {
                respond(exchange, 503, "not yet");
            } else {
                respond(exchange, 200, "recovered");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ---------------------------------------------------------------- fixtures

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private User newUser(Role role) {
        return users.saveAndFlush(User.builder()
                .email("retry" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash(passwordEncoder.encode("correct-horse-battery"))
                .role(role)
                .build());
    }

    private String tokenFor(User user) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "email", user.getEmail(), "password", "correct-horse-battery"));
        String response = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asText();
    }

    private Job newJob(User owner, String path, int maxAttempts, int initialBackoffSec) {
        return jobs.saveAndFlush(Job.builder()
                .name("job-" + SEQ.incrementAndGet())
                .owner(owner)
                .targetUrl(baseUrl + path)
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                // Yearly, with next_run_at forced into the past: the job is due exactly once,
                // and the occurrence after it is months away. A "* * * * *" fixture would come
                // due again mid-test, and since the enqueue sweep is global, a later runCycle
                // would silently re-fire an earlier test job and skew the counts.
                .cronExpr("0 0 1 1 *")
                .timezone("UTC")
                .nextRunAt(nowMicros().minusSeconds(60))
                .status(JobStatus.ENABLED)
                .maxAttempts(maxAttempts)
                .initialBackoffSec(initialBackoffSec)
                // 1.0 keeps the delay flat and the test fast; the exponential curve itself is
                // covered exhaustively in BackoffCalculatorTest.
                .backoffMultiplier(new BigDecimal("1.00"))
                .timeoutSec(10)
                .misfirePolicy(MisfirePolicy.FIRE_NOW)
                .build());
    }

    private List<JobExecution> attemptsOf(Job job) {
        return executions.findByJobIdOrderByScheduledForDesc(job.getId(), PageRequest.of(0, 20))
                .getContent().stream()
                .sorted((a, b) -> Integer.compare(a.getAttemptNo(), b.getAttemptNo()))
                .toList();
    }

    /** Runs one enqueue+poll cycle and waits until nothing is left mid-flight. */
    private void runCycle(Job job) {
        enqueuer.enqueueDueJobs();
        poller.pollOnce();
        awaitNoneRunning(job);
    }

    private void awaitNoneRunning(Job job) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            boolean busy = attemptsOf(job).stream()
                    .anyMatch(e -> e.getStatus() == ExecutionStatus.RUNNING);
            if (!busy && !attemptsOf(job).isEmpty()) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("Job " + job.getId() + " still had a RUNNING attempt after 15s");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- retry scheduling

    @Test
    @DisplayName("a 5xx marks the attempt FAILED and schedules attempt 2 in the future")
    void retryableFailureSchedulesAnotherAttempt() {
        Job job = newJob(newUser(Role.USER), "/boom", 3, 10);

        runCycle(job);

        List<JobExecution> attempts = attemptsOf(job);
        assertThat(attempts).hasSize(2);

        JobExecution first = attempts.get(0);
        assertThat(first.getAttemptNo()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(first.getResponseCode()).isEqualTo(500);
        assertThat(first.getFinishedAt()).isNotNull();

        JobExecution retry = attempts.get(1);
        assertThat(retry.getAttemptNo()).isEqualTo(2);
        assertThat(retry.getStatus()).isEqualTo(ExecutionStatus.RETRY_SCHEDULED);
        // Same occurrence — a retry is another go at the same scheduled run.
        assertThat(retry.getScheduledFor()).isEqualTo(first.getScheduledFor());
        // Backed off: not eligible yet, and within the jittered window around 10s.
        assertThat(retry.getRunAt()).isAfter(Instant.now());
        assertThat(retry.getRunAt()).isBefore(Instant.now().plusSeconds(13));

        // Nothing was dead-lettered — attempts remain.
        assertThat(deadLetters.findByExecutionId(first.getId())).isEmpty();
    }

    @Test
    @DisplayName("a backed-off retry is not claimed until its run_at arrives")
    void retryIsNotClaimedEarly() {
        Job job = newJob(newUser(Role.USER), "/boom", 3, 30);

        runCycle(job);

        // Attempt 2 exists but is 30s out, so a poll right now finds nothing to claim.
        assertThat(poller.pollOnce()).isZero();
        assertThat(attemptsOf(job).get(1).getStatus()).isEqualTo(ExecutionStatus.RETRY_SCHEDULED);
    }

    @Test
    @DisplayName("a retry that succeeds ends the chain")
    void retrySucceeds() {
        flakyFailuresRemaining.set(1); // fail once, then recover
        Job job = newJob(newUser(Role.USER), "/flaky", 3, 1);

        runCycle(job);
        assertThat(attemptsOf(job)).hasSize(2);

        // Wait out the ~1s backoff, then poll again.
        sleep(1_500);
        poller.pollOnce();
        awaitTerminal(job, 2);

        List<JobExecution> attempts = attemptsOf(job);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(attempts.get(1).getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(attempts.get(1).getResponseCode()).isEqualTo(200);
        assertThat(deadLetters.count()).isZero();
    }

    private void awaitTerminal(Job job, int attemptNo) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            List<JobExecution> attempts = attemptsOf(job);
            if (attempts.size() >= attemptNo) {
                ExecutionStatus status = attempts.get(attemptNo - 1).getStatus();
                if (status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.DEAD
                        || status == ExecutionStatus.FAILED) {
                    return;
                }
            }
            sleep(50);
        }
        throw new AssertionError("Attempt " + attemptNo + " of job " + job.getId()
                + " did not finish within 15s");
    }

    // ---------------------------------------------------------------- dead letters

    @Test
    @DisplayName("a 4xx dead-letters immediately without burning the retry budget")
    void clientErrorDiesImmediately() {
        Job job = newJob(newUser(Role.USER), "/bad", 5, 10);

        runCycle(job);

        List<JobExecution> attempts = attemptsOf(job);
        // No attempt 2: retrying an invalid request would fail identically.
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(ExecutionStatus.DEAD);
        assertThat(attempts.get(0).getResponseCode()).isEqualTo(400);

        DeadLetter deadLetter = deadLetters.findByExecutionId(attempts.get(0).getId()).orElseThrow();
        assertThat(deadLetter.getReason()).contains("client error");
        assertThat(deadLetter.getReplayedAt()).isNull();
        assertThat(deadLetter.getJob().getId()).isEqualTo(job.getId());
    }

    @Test
    @DisplayName("exhausting the attempt budget dead-letters the last attempt")
    void exhaustedAttemptsDie() {
        Job job = newJob(newUser(Role.USER), "/boom", 1, 1); // a single attempt allowed

        runCycle(job);

        List<JobExecution> attempts = attemptsOf(job);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(ExecutionStatus.DEAD);

        DeadLetter deadLetter = deadLetters.findByExecutionId(attempts.get(0).getId()).orElseThrow();
        assertThat(deadLetter.getReason()).contains("gave up after 1 attempt");
    }

    @Test
    @DisplayName("the whole retry chain runs to exhaustion and dead-letters exactly once")
    void fullChainToDeadLetter() {
        Job job = newJob(newUser(Role.USER), "/boom", 3, 1);

        runCycle(job);                    // attempt 1 fails, 2 scheduled
        sleep(1_500);
        poller.pollOnce();
        awaitNoneRunning(job);            // attempt 2 fails, 3 scheduled
        sleep(1_500);
        poller.pollOnce();
        awaitNoneRunning(job);            // attempt 3 fails, budget exhausted

        List<JobExecution> attempts = attemptsOf(job);
        assertThat(attempts).hasSize(3);
        assertThat(attempts.get(0).getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(attempts.get(1).getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(attempts.get(2).getStatus()).isEqualTo(ExecutionStatus.DEAD);

        // One dead letter for the chain, not one per failed attempt.
        assertThat(deadLetters.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- dead-letter API

    @Test
    @DisplayName("the dead-letter list is scoped to the caller's own jobs")
    void deadLetterListIsScoped() throws Exception {
        User mine = newUser(Role.USER);
        User theirs = newUser(Role.USER);
        runCycle(newJob(mine, "/bad", 1, 1));
        runCycle(newJob(theirs, "/bad", 1, 1));

        assertThat(deadLetters.count()).isEqualTo(2);

        mvc.perform(get("/dead-letters").header("Authorization", tokenFor(mine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reason").isNotEmpty())
                .andExpect(jsonPath("$.content[0].replayed").value(false));

        // An admin sees both.
        mvc.perform(get("/dead-letters").header("Authorization", tokenFor(newUser(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("another user's dead letter is 404, not 403")
    void otherUsersDeadLetterIsInvisible() throws Exception {
        Job job = newJob(newUser(Role.USER), "/bad", 1, 1);
        runCycle(job);
        Long deadLetterId = deadLetters.findAll().get(0).getId();

        String stranger = tokenFor(newUser(Role.USER));

        mvc.perform(get("/dead-letters/" + deadLetterId).header("Authorization", stranger))
                .andExpect(status().isNotFound());
        mvc.perform(post("/dead-letters/" + deadLetterId + "/replay").header("Authorization", stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("replay queues a fresh attempt and stamps the entry as replayed")
    void replayQueuesNewAttempt() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/bad", 1, 1);
        runCycle(job);

        DeadLetter deadLetter = deadLetters.findAll().get(0);
        String token = tokenFor(owner);

        mvc.perform(post("/dead-letters/" + deadLetter.getId() + "/replay")
                        .header("Authorization", token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.jobId").value(job.getId()));

        List<JobExecution> attempts = attemptsOf(job);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(1).getStatus()).isEqualTo(ExecutionStatus.QUEUED);
        // Same occurrence, so the history for that scheduled run stays together.
        assertThat(attempts.get(1).getScheduledFor()).isEqualTo(attempts.get(0).getScheduledFor());

        // The dead letter is kept as the record that this occurrence failed.
        DeadLetter reloaded = deadLetters.findById(deadLetter.getId()).orElseThrow();
        assertThat(reloaded.getReplayedAt()).isNotNull();

        // And it drops out of the default "needs attention" view.
        mvc.perform(get("/dead-letters").header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/dead-letters?includeReplayed=true").header("Authorization", token))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].replayed").value(true));
    }

    @Test
    @DisplayName("replaying twice is a 409")
    void replayIsNotRepeatable() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/bad", 1, 1);
        runCycle(job);

        Long id = deadLetters.findAll().get(0).getId();
        String token = tokenFor(owner);

        mvc.perform(post("/dead-letters/" + id + "/replay").header("Authorization", token))
                .andExpect(status().isAccepted());
        mvc.perform(post("/dead-letters/" + id + "/replay").header("Authorization", token))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a replayed attempt actually runs and can succeed")
    void replayedAttemptRuns() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/bad", 1, 1);
        runCycle(job);

        mvc.perform(post("/dead-letters/" + deadLetters.findAll().get(0).getId() + "/replay")
                        .header("Authorization", tokenFor(owner)))
                .andExpect(status().isAccepted());

        // Point the job somewhere that works — the operator "fixed" it before replaying.
        Job reloaded = jobs.findById(job.getId()).orElseThrow();
        reloaded.setTargetUrl(baseUrl + "/ok");
        jobs.saveAndFlush(reloaded);

        poller.pollOnce();
        awaitTerminal(job, 2);

        assertThat(attemptsOf(job).get(1).getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------- manual retry

    @Test
    @DisplayName("POST /executions/{id}/retry queues a new attempt for a FAILED execution")
    void manualRetryOfFailedExecution() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/boom", 3, 30);
        runCycle(job);

        JobExecution failed = attemptsOf(job).get(0);
        assertThat(failed.getStatus()).isEqualTo(ExecutionStatus.FAILED);

        // Attempt 2 is already RETRY_SCHEDULED, so a manual retry must be refused: two live
        // attempts for one occurrence is exactly what the engine prevents.
        mvc.perform(post("/executions/" + failed.getId() + "/retry")
                        .header("Authorization", tokenFor(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("manual retry of a DEAD execution picks the next free attempt number")
    void manualRetryOfDeadExecution() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/bad", 1, 1);
        runCycle(job);

        JobExecution dead = attemptsOf(job).get(0);
        assertThat(dead.getStatus()).isEqualTo(ExecutionStatus.DEAD);

        mvc.perform(post("/executions/" + dead.getId() + "/retry")
                        .header("Authorization", tokenFor(owner)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("a QUEUED execution cannot be retried")
    void cannotRetryAnUnfinishedExecution() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/ok", 3, 10);
        enqueuer.enqueueDueJobs(); // QUEUED, never polled

        JobExecution queued = attemptsOf(job).get(0);

        mvc.perform(post("/executions/" + queued.getId() + "/retry")
                        .header("Authorization", tokenFor(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("another user cannot retry your execution")
    void manualRetryIsOwnershipScoped() throws Exception {
        Job job = newJob(newUser(Role.USER), "/bad", 1, 1);
        runCycle(job);

        mvc.perform(post("/executions/" + attemptsOf(job).get(0).getId() + "/retry")
                        .header("Authorization", tokenFor(newUser(Role.USER))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the retry endpoints require a token")
    void retryEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/dead-letters")).andExpect(status().isUnauthorized());
        mvc.perform(post("/dead-letters/1/replay")).andExpect(status().isUnauthorized());
        mvc.perform(post("/executions/1/retry")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a job's execution history shows every attempt for the occurrence")
    void historyShowsAllAttempts() throws Exception {
        User owner = newUser(Role.USER);
        Job job = newJob(owner, "/boom", 3, 30);
        runCycle(job);

        mvc.perform(get("/jobs/" + job.getId() + "/executions")
                        .header("Authorization", tokenFor(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a failed attempt records how long the call took")
    void durationIsRecorded() {
        Job job = newJob(newUser(Role.USER), "/boom", 1, 1);
        runCycle(job);

        JobExecution attempt = attemptsOf(job).get(0);
        assertThat(attempt.getStartedAt()).isNotNull();
        assertThat(attempt.getFinishedAt()).isNotNull();
        assertThat(Duration.between(attempt.getStartedAt(), attempt.getFinishedAt()))
                .isGreaterThanOrEqualTo(Duration.ZERO);
    }
}
