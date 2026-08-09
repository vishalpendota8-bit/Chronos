package com.chronos.persistence;

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
import com.chronos.support.PostgresTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the JPA mappings line up with the Flyway schema. Because ddl-auto is `validate`,
 * this test failing to even start the context is itself a meaningful signal: it means an
 * entity and V1__init.sql disagree.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SchemaAndRepositoryIT extends PostgresTestBase {

    /** Unique-ish suffix so rows from different tests in the shared database never collide. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final UserRepository users;
    private final JobRepository jobs;
    private final JobExecutionRepository executions;
    private final DeadLetterRepository deadLetters;
    private final EntityManager em;

    private User owner;

    @Autowired
    SchemaAndRepositoryIT(UserRepository users, JobRepository jobs, JobExecutionRepository executions,
                          DeadLetterRepository deadLetters, EntityManager em) {
        this.users = users;
        this.jobs = jobs;
        this.executions = executions;
        this.deadLetters = deadLetters;
        this.em = em;
    }

    @BeforeEach
    void createOwner() {
        owner = users.save(User.builder()
                .email("owner" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("{noop}irrelevant")
                .role(Role.USER)
                .build());
    }

    private Job newJob(String name) {
        return Job.builder()
                .name(name + "-" + SEQ.incrementAndGet())
                .owner(owner)
                .targetUrl("https://example.test/hook")
                .httpMethod(HttpMethodType.POST)
                .headers(Map.of("X-Tenant", "acme"))
                .payload("{\"hello\":\"world\"}")
                .cronExpr("0 * * * *")
                .timezone("UTC")
                .nextRunAt(Instant.parse("2026-03-07T11:00:00Z"))
                .status(JobStatus.ENABLED)
                .maxAttempts(3)
                .initialBackoffSec(10)
                .backoffMultiplier(new BigDecimal("2.00"))
                .timeoutSec(30)
                .misfirePolicy(com.chronos.job.MisfirePolicy.FIRE_NOW)
                .build();
    }

    @Test
    @DisplayName("email lookup is case-insensitive and the unique index is enforced on lower(email)")
    void userEmailIsCaseInsensitive() {
        assertThat(users.findByEmailIgnoreCase(owner.getEmail().toUpperCase())).isPresent();
        assertThat(users.existsByEmailIgnoreCase(owner.getEmail())).isTrue();

        // GenerationType.IDENTITY means persist() must INSERT immediately to obtain the id,
        // so the constraint fires inside save() rather than at flush time.
        assertThatThrownBy(() -> users.saveAndFlush(User.builder()
                .email(owner.getEmail().toUpperCase())
                .passwordHash("x")
                .role(Role.USER)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("jsonb columns round-trip a typed header map and a raw payload string")
    void jsonbColumnsRoundTrip() {
        Job saved = jobs.saveAndFlush(newJob("json"));
        em.clear(); // force a real SELECT rather than reading back the first-level cache

        Job loaded = jobs.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getHeaders()).containsExactlyEntriesOf(Map.of("X-Tenant", "acme"));
        assertThat(loaded.getPayload()).contains("\"hello\"");
        assertThat(loaded.getBackoffMultiplier()).isEqualByComparingTo("2.00");
        assertThat(loaded.getCreatedAt()).isNotNull(); // filled by the column DEFAULT now()
    }

    @Test
    @DisplayName("ownership-scoped lookup hides other users' jobs")
    void findByIdAndOwnerIdIsScoped() {
        Job saved = jobs.saveAndFlush(newJob("scoped"));
        User stranger = users.save(User.builder()
                .email("stranger" + SEQ.incrementAndGet() + "@chronos.test")
                .passwordHash("x").role(Role.USER).build());

        assertThat(jobs.findByIdAndOwnerId(saved.getId(), owner.getId())).isPresent();
        assertThat(jobs.findByIdAndOwnerId(saved.getId(), stranger.getId())).isEmpty();
    }

    @Test
    @DisplayName("a user cannot have two jobs with the same name")
    void jobNameIsUniquePerOwner() {
        Job first = jobs.saveAndFlush(newJob("dup"));

        Job clash = newJob("other");
        clash.setName(first.getName());

        assertThatThrownBy(() -> jobs.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same cron occurrence cannot be enqueued twice — the anti-duplicate guarantee")
    void duplicateOccurrenceIsRejected() {
        Job job = jobs.saveAndFlush(newJob("dupe-occurrence"));
        Instant occurrence = Instant.parse("2026-03-07T11:00:00Z");

        executions.saveAndFlush(execution(job, occurrence, 1, ExecutionStatus.QUEUED));

        // Simulates two scheduler nodes both trying to enqueue the same cron occurrence.
        assertThatThrownBy(() ->
                executions.saveAndFlush(execution(job, occurrence, 1, ExecutionStatus.QUEUED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("retry attempts for the same occurrence are allowed (different attempt_no)")
    void retryAttemptsShareAnOccurrence() {
        Job job = jobs.saveAndFlush(newJob("retries"));
        Instant occurrence = Instant.parse("2026-03-07T11:00:00Z");

        executions.saveAndFlush(execution(job, occurrence, 1, ExecutionStatus.FAILED));
        executions.saveAndFlush(execution(job, occurrence, 2, ExecutionStatus.RETRY_SCHEDULED));

        assertThat(executions.findByJobIdOrderByScheduledForDesc(job.getId(), PageRequest.of(0, 10)))
                .hasSize(2);
        assertThat(executions.countByJobIdAndStatus(job.getId(), ExecutionStatus.FAILED)).isEqualTo(1);
    }

    @Test
    @DisplayName("timestamps survive the round trip at microsecond precision")
    void instantsRoundTrip() {
        Job job = jobs.saveAndFlush(newJob("time"));
        Instant occurrence = Instant.parse("2026-03-07T11:00:00Z").plusNanos(123_456_000);
        Instant truncated = occurrence.truncatedTo(ChronoUnit.MICROS);

        JobExecution saved = executions.saveAndFlush(execution(job, truncated, 1, ExecutionStatus.QUEUED));
        em.clear();

        assertThat(executions.findById(saved.getId()).orElseThrow().getScheduledFor())
                .isEqualTo(truncated);
    }

    @Test
    @DisplayName("a dead letter links an execution to its job and can only exist once per execution")
    void deadLetterMapping() {
        Job job = jobs.saveAndFlush(newJob("dead"));
        JobExecution exec = executions.saveAndFlush(
                execution(job, Instant.parse("2026-03-07T11:00:00Z"), 3, ExecutionStatus.DEAD));

        deadLetters.saveAndFlush(DeadLetter.builder()
                .execution(exec)
                .job(job)
                .reason("HTTP 500 after 3 attempts")
                .failedAt(Instant.parse("2026-03-07T11:05:00Z"))
                .build());

        assertThat(deadLetters.findByExecutionId(exec.getId())).isPresent();
        assertThat(deadLetters.findByJobOwnerIdAndReplayedAtIsNullOrderByFailedAtDesc(
                owner.getId(), PageRequest.of(0, 10))).hasSize(1);

        assertThatThrownBy(() -> deadLetters.saveAndFlush(DeadLetter.builder()
                .execution(exec).job(job).reason("again").failedAt(Instant.now()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("check constraints reject out-of-range job settings")
    void checkConstraintsHold() {
        Job job = newJob("bad-timeout");
        job.setTimeoutSec(9_999);

        assertThatThrownBy(() -> jobs.saveAndFlush(job))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private JobExecution execution(Job job, Instant occurrence, int attempt, ExecutionStatus status) {
        return JobExecution.builder()
                .job(job)
                .attemptNo(attempt)
                .scheduledFor(occurrence)
                .runAt(occurrence)
                .status(status)
                .build();
    }
}
