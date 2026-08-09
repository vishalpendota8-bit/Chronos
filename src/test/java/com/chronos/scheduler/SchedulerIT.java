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
import com.chronos.support.PostgresTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine end to end: enqueue → claim → dispatch → record, against real Postgres and a real
 * HTTP target.
 *
 * <p>The {@code @Scheduled} timers are disabled in the test profile, so each phase is driven
 * explicitly. That makes the assertions deterministic — no sleeping and hoping a tick landed.
 *
 * <p>Not {@code @Transactional}: the dispatch happens on a different thread, so anything this
 * test wrote inside an uncommitted transaction would be invisible to it.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchedulerIT extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final UserRepository users;
    private final ExecutionEnqueuer enqueuer;
    private final ExecutionPoller poller;
    private final TransactionTemplate txTemplate;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger okHits = new AtomicInteger();
    private final List<String> idempotencyKeys = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    SchedulerIT(JobRepository jobs, JobExecutionRepository executions, UserRepository users,
                ExecutionEnqueuer enqueuer, ExecutionPoller poller, TransactionTemplate txTemplate) {
        this.jobs = jobs;
        this.executions = executions;
        this.users = users;
        this.enqueuer = enqueuer;
        this.poller = poller;
        this.txTemplate = txTemplate;
    }

    @BeforeEach
    void setUp() throws IOException {
        // The shared container carries rows from other IT classes, and each test here asserts on
        // exact counts ("the sweep enqueued nothing"). Start from an empty slate — executions
        // first, since jobs are their parent.
        executions.deleteAllInBatch();
        jobs.deleteAllInBatch();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            idempotencyKeys.add(exchange.getRequestHeaders()
                    .getFirst(HttpDispatcher.IDEMPOTENCY_HEADER));
            respond(exchange, 200, "{\"received\":true}");
        });
        server.createContext("/boom", exchange -> respond(exchange, 500, "kaboom"));
        server.createContext("/bad", exchange -> respond(exchange, 400, "nope"));
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

    /**
     * Postgres stores timestamptz at microsecond precision, but Instant.now() carries
     * nanoseconds. Production never hits this — every nextRunAt comes from CronService, which
     * truncates for exactly this reason — but these fixtures set the column directly, so they
     * have to truncate too or a value read back will not equal the one written.
     */
    private static Instant nowMicros() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private User newUser() {
        return users.saveAndFlush(User.builder()
                .email("sched" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("irrelevant")
                .role(Role.USER)
                .build());
    }

    /** A job that is already due: next_run_at is in the past. */
    private Job newDueJob(String path) {
        return newJob(path, nowMicros().minusSeconds(60), JobStatus.ENABLED);
    }

    private Job newJob(String path, Instant nextRunAt, JobStatus status) {
        return jobs.saveAndFlush(Job.builder()
                .name("job-" + SEQ.incrementAndGet())
                .owner(newUser())
                .targetUrl(baseUrl + path)
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                // Every minute, so the next occurrence is always close by.
                .cronExpr("* * * * *")
                .timezone("UTC")
                .nextRunAt(nextRunAt)
                .status(status)
                .maxAttempts(3)
                .initialBackoffSec(10)
                .backoffMultiplier(new BigDecimal("2.00"))
                .timeoutSec(30)
                .build());
    }

    /** Waits for an execution to reach a terminal-ish state, since dispatch is asynchronous. */
    private JobExecution awaitStatus(Long executionId, Duration timeout, ExecutionStatus... accepted) {
        List<ExecutionStatus> wanted = List.of(accepted);
        Instant deadline = Instant.now().plus(timeout);

        while (Instant.now().isBefore(deadline)) {
            JobExecution execution = executions.findById(executionId).orElseThrow();
            if (wanted.contains(execution.getStatus())) {
                return execution;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Execution " + executionId + " did not reach " + wanted
                + " within " + timeout + " (was "
                + executions.findById(executionId).orElseThrow().getStatus() + ")");
    }

    private JobExecution onlyExecutionOf(Job job) {
        List<JobExecution> found = executions.findByJobIdOrderByScheduledForDesc(
                job.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    // ---------------------------------------------------------------- enqueue

    @Test
    @DisplayName("a due job becomes a QUEUED execution and its next_run_at moves forward")
    void enqueueCreatesExecutionAndAdvancesPointer() {
        Job job = newDueJob("/ok");
        Instant originalNextRun = job.getNextRunAt();

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);

        JobExecution execution = onlyExecutionOf(job);
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(execution.getAttemptNo()).isEqualTo(1);
        assertThat(execution.getScheduledFor()).isEqualTo(originalNextRun);
        assertThat(execution.getRunAt()).isEqualTo(originalNextRun);
        assertThat(execution.getClaimedAt()).isNull();

        Job reloaded = jobs.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getNextRunAt()).isAfter(originalNextRun);
    }

    @Test
    @DisplayName("a second sweep does not duplicate the occurrence — the overlap guard holds")
    void enqueueIsNotDuplicated() {
        Job job = newDueJob("/ok");

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);
        // The first occurrence is still QUEUED, so nothing new may be enqueued for this job.
        assertThat(enqueuer.enqueueDueJobs()).isZero();

        assertThat(executions.findByJobIdOrderByScheduledForDesc(
                job.getId(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(1);
    }

    @Test
    @DisplayName("paused and archived jobs are never enqueued")
    void suspendedJobsAreSkipped() {
        Job paused = newJob("/ok", nowMicros().minusSeconds(60), JobStatus.PAUSED);
        Job archived = newJob("/ok", nowMicros().minusSeconds(60), JobStatus.ARCHIVED);

        assertThat(enqueuer.enqueueDueJobs()).isZero();

        assertThat(executions.findByJobIdOrderByScheduledForDesc(
                paused.getId(), org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
        assertThat(executions.findByJobIdOrderByScheduledForDesc(
                archived.getId(), org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
    }

    @Test
    @DisplayName("a job whose next run is still in the future is not enqueued")
    void futureJobsAreSkipped() {
        newJob("/ok", nowMicros().plusSeconds(3600), JobStatus.ENABLED);

        assertThat(enqueuer.enqueueDueJobs()).isZero();
    }

    // ---------------------------------------------------------------- dispatch

    @Test
    @DisplayName("a successful dispatch records SUCCEEDED with the response, and sends the idempotency key")
    void successfulDispatch() {
        Job job = newDueJob("/ok");
        enqueuer.enqueueDueJobs();
        Long executionId = onlyExecutionOf(job).getId();

        assertThat(poller.pollOnce()).isEqualTo(1);

        JobExecution finished = awaitStatus(executionId, Duration.ofSeconds(10),
                ExecutionStatus.SUCCEEDED);

        assertThat(finished.getResponseCode()).isEqualTo(200);
        assertThat(finished.getResponseSnippet()).contains("received");
        assertThat(finished.getErrorMessage()).isNull();
        assertThat(finished.getClaimedAt()).isNotNull();
        assertThat(finished.getStartedAt()).isNotNull();
        assertThat(finished.getFinishedAt()).isNotNull();

        assertThat(okHits.get()).isEqualTo(1);
        // The header the target de-duplicates on must be this execution's id.
        assertThat(idempotencyKeys).containsExactly(String.valueOf(executionId));
    }

    @Test
    @DisplayName("a 500 is recorded as FAILED with the status code preserved")
    void serverErrorIsRecordedAsFailed() {
        Job job = newDueJob("/boom");
        enqueuer.enqueueDueJobs();
        Long executionId = onlyExecutionOf(job).getId();

        poller.pollOnce();

        JobExecution finished = awaitStatus(executionId, Duration.ofSeconds(10),
                ExecutionStatus.FAILED);

        assertThat(finished.getResponseCode()).isEqualTo(500);
        assertThat(finished.getResponseSnippet()).isEqualTo("kaboom");
        assertThat(finished.getErrorMessage()).contains("500");
        assertThat(finished.getFinishedAt()).isNotNull();
        // Retryable with attempts left, so M5 adds a second attempt alongside this one.
        // RetryIT asserts the backoff and the rest of the chain.
        assertThat(executions.findByJobIdOrderByScheduledForDesc(
                job.getId(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(2);
    }

    @Test
    @DisplayName("a 4xx goes straight to DEAD — retrying an invalid request cannot help")
    void clientErrorDiesImmediately() {
        Job job = newDueJob("/bad");
        enqueuer.enqueueDueJobs();
        Long executionId = onlyExecutionOf(job).getId();

        poller.pollOnce();

        // M5 made this terminal: a non-retryable outcome skips the retry budget entirely and
        // dead-letters on the first attempt. RetryIT covers the dead letter itself.
        JobExecution finished = awaitStatus(executionId, Duration.ofSeconds(10),
                ExecutionStatus.DEAD);
        assertThat(finished.getResponseCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a target that refuses the connection fails with an error message and no status")
    void connectionFailureIsRecorded() {
        Job job = jobs.saveAndFlush(Job.builder()
                .name("unreachable-" + SEQ.incrementAndGet())
                .owner(newUser())
                .targetUrl("http://127.0.0.1:1/nowhere")
                .httpMethod(HttpMethodType.POST)
                .payload("{\"ping\":1}")
                .cronExpr("* * * * *")
                .timezone("UTC")
                .nextRunAt(nowMicros().minusSeconds(60))
                .status(JobStatus.ENABLED)
                .maxAttempts(3)
                .initialBackoffSec(10)
                .backoffMultiplier(new BigDecimal("2.00"))
                .timeoutSec(5)
                .build());

        enqueuer.enqueueDueJobs();
        Long executionId = onlyExecutionOf(job).getId();

        poller.pollOnce();

        JobExecution finished = awaitStatus(executionId, Duration.ofSeconds(15),
                ExecutionStatus.FAILED);
        assertThat(finished.getResponseCode()).isNull();
        assertThat(finished.getErrorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("polling twice does not dispatch the same execution twice")
    void claimedRowsAreNotClaimedAgain() {
        Job job = newDueJob("/ok");
        enqueuer.enqueueDueJobs();
        Long executionId = onlyExecutionOf(job).getId();

        assertThat(poller.pollOnce()).isEqualTo(1);
        // The row is RUNNING (or already finished), and the claim query only looks at
        // QUEUED/RETRY_SCHEDULED — so there is nothing left to claim.
        assertThat(poller.pollOnce()).isZero();

        awaitStatus(executionId, Duration.ofSeconds(10), ExecutionStatus.SUCCEEDED);
        assertThat(okHits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the full loop runs an occurrence exactly once and then schedules the next")
    void endToEndLoop() {
        Job job = newDueJob("/ok");

        enqueuer.enqueueDueJobs();
        poller.pollOnce();
        Long firstId = onlyExecutionOf(job).getId();
        awaitStatus(firstId, Duration.ofSeconds(10), ExecutionStatus.SUCCEEDED);

        // With the first occurrence finished, the guard releases and the next one is enqueued —
        // provided its time has come. next_run_at was advanced past now, so force it due.
        Job reloaded = jobs.findById(job.getId()).orElseThrow();
        reloaded.setNextRunAt(nowMicros().minusSeconds(1));
        jobs.saveAndFlush(reloaded);

        assertThat(enqueuer.enqueueDueJobs()).isEqualTo(1);
        assertThat(executions.findByJobIdOrderByScheduledForDesc(
                job.getId(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(2);
    }

    // ---------------------------------------------------------------- SKIP LOCKED

    @Test
    @DisplayName("two concurrent pollers claim disjoint batches — the SKIP LOCKED guarantee")
    void concurrentPollersDoNotOverlap() throws Exception {
        // Ten due executions for one job, ten distinct occurrences.
        Job job = newJob("/ok", null, JobStatus.PAUSED); // paused so the enqueuer ignores it
        Instant base = nowMicros().minus(Duration.ofHours(2));
        for (int i = 0; i < 10; i++) {
            executions.saveAndFlush(JobExecution.builder()
                    .job(job)
                    .attemptNo(1)
                    .scheduledFor(base.plusSeconds(i * 60L))
                    .runAt(base.plusSeconds(i * 60L))
                    .status(ExecutionStatus.QUEUED)
                    .build());
        }

        /*
         * Both threads open a transaction, run the claiming query with a limit of 5, and then
         * hold their transaction open until the other has claimed too. That overlap is the whole
         * point: it forces the second query to run while the first still holds its row locks.
         *
         * Without SKIP LOCKED the second thread would block on those locks and, once released,
         * re-read and return the SAME rows — both nodes dispatching the same jobs. With it, the
         * second thread steps over the locked rows and takes the next five.
         */
        CountDownLatch bothClaimed = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> txTemplate.execute(tx -> {
                    List<Long> ids = executions.claimDue(Instant.now(), 5).stream()
                            .map(JobExecution::getId)
                            .toList();
                    bothClaimed.countDown();
                    try {
                        bothClaimed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ids;
                })));
            }

            List<Long> first = futures.get(0).get(30, TimeUnit.SECONDS);
            List<Long> second = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(first).hasSize(5);
            assertThat(second).hasSize(5);
            // The guarantee: no execution was handed to both pollers.
            assertThat(first).doesNotContainAnyElementsOf(second);

        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("claiming marks rows RUNNING with a claimed_at stamp")
    void claimMarksRowsRunning() {
        Job job = newDueJob("/ok");
        enqueuer.enqueueDueJobs();
        Long executionId = onlyExecutionOf(job).getId();

        poller.pollOnce();

        // Either still RUNNING or already finished by the dispatch thread; both prove the claim
        // happened, and claimed_at is set either way.
        JobExecution execution = awaitStatus(executionId, Duration.ofSeconds(10),
                ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED);
        assertThat(execution.getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("an execution whose run_at is still in the future is not claimed")
    void futureExecutionsAreNotClaimed() {
        Job job = newJob("/ok", null, JobStatus.PAUSED);
        executions.saveAndFlush(JobExecution.builder()
                .job(job)
                .attemptNo(1)
                .scheduledFor(nowMicros().plusSeconds(3600))
                .runAt(nowMicros().plusSeconds(3600))
                .status(ExecutionStatus.QUEUED)
                .build());

        assertThat(poller.pollOnce()).isZero();
    }
}
