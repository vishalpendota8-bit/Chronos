package com.chronos.job;

import com.chronos.auth.User;
import com.chronos.auth.UserRepository;
import com.chronos.common.BadRequestException;
import com.chronos.common.ConflictException;
import com.chronos.common.NotFoundException;
import com.chronos.common.PageResponse;
import com.chronos.config.JobDefaults;
import com.chronos.job.dto.CronPreviewRequest;
import com.chronos.job.dto.CronPreviewResponse;
import com.chronos.job.dto.JobRequest;
import com.chronos.job.dto.JobResponse;
import com.chronos.scheduler.CronService;
import com.chronos.security.ChronosUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Job CRUD, schedule state transitions, and the ownership rules that decide who may see what.
 *
 * <p><b>The ownership model in one line:</b> a USER may only ever touch jobs whose
 * {@code owner_id} is their own id; an ADMIN may touch any job. Every read and write funnels
 * through {@link #loadForCaller}, so there is exactly one place to get that wrong — and one
 * place to audit.
 *
 * <p><b>What this module does and does not schedule:</b> {@code nextRunAt} is computed here and
 * is the hand-off point to M4. No {@code job_executions} row is created yet — the poller has
 * nothing to claim until M4 materialises occurrences from {@code nextRunAt}.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private static final int DEFAULT_PREVIEW_COUNT = 5;

    private final JobRepository jobs;
    private final UserRepository users;
    private final CronService cronService;
    private final JobValidator validator;
    private final JobDefaults defaults;

    public JobService(JobRepository jobs, UserRepository users, CronService cronService,
                      JobValidator validator, JobDefaults defaults) {
        this.jobs = jobs;
        this.users = users;
        this.cronService = cronService;
        this.validator = validator;
        this.defaults = defaults;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Lists jobs visible to the caller.
     *
     * <p>ARCHIVED rows are hidden unless explicitly requested — that is what makes DELETE feel
     * like a delete while the execution history underneath stays intact.
     */
    @Transactional(readOnly = true)
    public PageResponse<JobResponse> list(ChronosUserDetails caller, boolean includeArchived,
                                          Pageable pageable) {
        Page<Job> page;
        if (caller.isAdmin()) {
            page = includeArchived
                    ? jobs.findAll(pageable)
                    : jobs.findByStatusNot(JobStatus.ARCHIVED, pageable);
        } else {
            page = includeArchived
                    ? jobs.findByOwnerId(caller.id(), pageable)
                    : jobs.findByOwnerIdAndStatusNot(caller.id(), JobStatus.ARCHIVED, pageable);
        }
        return PageResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public JobResponse get(Long id, ChronosUserDetails caller) {
        return toResponse(loadForCaller(id, caller));
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public JobResponse create(JobRequest request, ChronosUserDetails caller) {
        validator.validate(request);

        String name = request.name().trim();
        String timezone = orDefault(request.timezone(), defaults.timezone()).trim();

        // Validates expression and zone together and throws a readable 400 for either.
        cronService.validate(request.cronExpr(), timezone);

        if (jobs.existsByOwnerIdAndNameIgnoreCase(caller.id(), name)) {
            throw new ConflictException("You already have a job named '" + name + "'");
        }

        // getReferenceById returns a lazy proxy: we only need something to put in the FK column,
        // and loading the whole owner row to write its id would be a wasted SELECT.
        User owner = users.getReferenceById(caller.id());

        Job job = Job.builder()
                .name(name)
                .owner(owner)
                .targetUrl(request.targetUrl().trim())
                .httpMethod(request.httpMethod())
                .headers(request.headers())
                .payload(blankToNull(request.payload()))
                .cronExpr(request.cronExpr().trim())
                .timezone(timezone)
                .status(JobStatus.ENABLED)
                .maxAttempts(orDefault(request.maxAttempts(), defaults.maxAttempts()))
                .initialBackoffSec(orDefault(request.initialBackoffSec(), defaults.initialBackoffSec()))
                .backoffMultiplier(orDefault(request.backoffMultiplier(), defaults.backoffMultiplier()))
                .timeoutSec(orDefault(request.timeoutSec(), defaults.timeoutSec()))
                // A new job is ENABLED, so it gets a schedule immediately.
                .nextRunAt(nextRunOrNull(request.cronExpr().trim(), timezone))
                .build();

        try {
            job = jobs.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            // The (owner_id, name) unique index is the real guarantee; the check above is only
            // there to produce a friendly message in the common, uncontended case.
            throw new ConflictException("You already have a job named '" + name + "'");
        }

        log.info("User {} created job {} ('{}'), next run {}",
                caller.id(), job.getId(), job.getName(), job.getNextRunAt());
        return toResponse(job);
    }

    /**
     * Full replacement of a job's definition.
     *
     * <p>{@code nextRunAt} is recomputed only when the schedule actually changed. Recomputing it
     * unconditionally would silently push the next run forward every time someone edited an
     * unrelated field like the timeout — a job edited once a minute would never fire.
     */
    @Transactional
    public JobResponse update(Long id, JobRequest request, ChronosUserDetails caller) {
        Job job = loadForCaller(id, caller);
        requireNotArchived(job, "updated");
        validator.validate(request);

        String name = request.name().trim();
        String timezone = orDefault(request.timezone(), defaults.timezone()).trim();
        String cronExpr = request.cronExpr().trim();

        cronService.validate(cronExpr, timezone);

        if (jobs.existsByOwnerIdAndNameIgnoreCaseAndIdNot(job.getOwner().getId(), name, id)) {
            throw new ConflictException("You already have a job named '" + name + "'");
        }

        boolean scheduleChanged = !cronExpr.equals(job.getCronExpr())
                || !timezone.equals(job.getTimezone());

        job.setName(name);
        job.setTargetUrl(request.targetUrl().trim());
        job.setHttpMethod(request.httpMethod());
        job.setHeaders(request.headers());
        job.setPayload(blankToNull(request.payload()));
        job.setCronExpr(cronExpr);
        job.setTimezone(timezone);
        job.setMaxAttempts(orDefault(request.maxAttempts(), defaults.maxAttempts()));
        job.setInitialBackoffSec(orDefault(request.initialBackoffSec(), defaults.initialBackoffSec()));
        job.setBackoffMultiplier(orDefault(request.backoffMultiplier(), defaults.backoffMultiplier()));
        job.setTimeoutSec(orDefault(request.timeoutSec(), defaults.timeoutSec()));

        if (scheduleChanged && job.getStatus() == JobStatus.ENABLED) {
            job.setNextRunAt(nextRunOrNull(cronExpr, timezone));
            log.info("Job {} schedule changed; next run is now {}", id, job.getNextRunAt());
        }

        try {
            // The entity is managed, so this flush is what surfaces a unique-index collision as
            // an exception we can translate rather than a failure at commit time.
            jobs.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You already have a job named '" + name + "'");
        }

        return toResponse(job);
    }

    /**
     * Soft delete: the job becomes ARCHIVED and stops scheduling, but the row and all of its
     * execution history survive.
     *
     * <p>Tradeoff: a hard delete would cascade away every {@code job_executions} and
     * {@code dead_letters} row, destroying the audit trail of what already ran. Keeping the row
     * costs storage and means the (owner_id, name) unique index still reserves the name — an
     * archived "nightly-report" blocks creating a new job by that name. Accepted: an operator
     * deleting a job rarely wants its history gone too.
     *
     * <p>Idempotent — archiving an already-archived job is a no-op, so a retried DELETE is safe.
     */
    @Transactional
    public void archive(Long id, ChronosUserDetails caller) {
        Job job = loadForCaller(id, caller);
        if (job.getStatus() == JobStatus.ARCHIVED) {
            return;
        }

        job.setStatus(JobStatus.ARCHIVED);
        // Clearing nextRunAt is what actually stops M4 from enqueueing further occurrences.
        job.setNextRunAt(null);

        log.info("User {} archived job {}", caller.id(), id);
    }

    /**
     * Suspends the schedule. Idempotent, so a double-clicked button is harmless.
     *
     * <p><b>Note for M4:</b> pausing clears {@code nextRunAt}, which stops <em>new</em>
     * occurrences from being enqueued. Executions already queued or mid-retry are not cancelled
     * here — cancelling in-flight work is M6's concern.
     */
    @Transactional
    public JobResponse pause(Long id, ChronosUserDetails caller) {
        Job job = loadForCaller(id, caller);
        requireNotArchived(job, "paused");

        if (job.getStatus() != JobStatus.PAUSED) {
            job.setStatus(JobStatus.PAUSED);
            job.setNextRunAt(null);
            log.info("User {} paused job {}", caller.id(), id);
        }
        return toResponse(job);
    }

    /**
     * Resumes a paused job.
     *
     * <p>The next run is computed from <em>now</em>, not from where the schedule was when it was
     * paused. A job paused over a weekend therefore does not fire a burst of catch-up runs on
     * resume; it simply rejoins its schedule. That "skip what was missed" choice is the misfire
     * policy, and M6 makes it configurable.
     */
    @Transactional
    public JobResponse resume(Long id, ChronosUserDetails caller) {
        Job job = loadForCaller(id, caller);
        requireNotArchived(job, "resumed");

        if (job.getStatus() != JobStatus.ENABLED) {
            job.setStatus(JobStatus.ENABLED);
            job.setNextRunAt(nextRunOrNull(job.getCronExpr(), job.getTimezone()));
            log.info("User {} resumed job {}, next run {}", caller.id(), id, job.getNextRunAt());
        }
        return toResponse(job);
    }

    // ------------------------------------------------------------------ cron preview

    /** Stateless helper for the create/edit form — validates and previews without saving. */
    public CronPreviewResponse previewCron(CronPreviewRequest request) {
        String timezone = orDefault(request.timezone(), defaults.timezone()).trim();
        String cronExpr = request.cronExpr().trim();

        cronService.validate(cronExpr, timezone);

        return new CronPreviewResponse(
                cronExpr,
                timezone,
                cronService.describe(cronExpr),
                cronService.preview(cronExpr, timezone, Instant.now(),
                        orDefault(request.count(), DEFAULT_PREVIEW_COUNT)));
    }

    // ------------------------------------------------------------------ internals

    /**
     * The single ownership gate.
     *
     * <p>A USER's lookup is scoped by {@code owner_id} in the query itself, so another user's
     * job is simply not found. It reports 404 rather than 403 on purpose: a 403 would confirm
     * that the id exists and leak how many jobs other tenants have. See {@link NotFoundException}.
     */
    private Job loadForCaller(Long id, ChronosUserDetails caller) {
        Optional<Job> job = caller.isAdmin()
                ? jobs.findById(id)
                : jobs.findByIdAndOwnerId(id, caller.id());

        return job.orElseThrow(() -> NotFoundException.of("Job", id));
    }

    /** ARCHIVED is terminal: an archived job is a historical record, not an editable one. */
    private void requireNotArchived(Job job, String verb) {
        if (job.getStatus() == JobStatus.ARCHIVED) {
            throw new BadRequestException("Job " + job.getId() + " is archived and cannot be " + verb);
        }
    }

    /**
     * Null means "this cron has no further occurrence" — cron-utils reports that for genuinely
     * unsatisfiable dates such as 31 February. The job stays ENABLED but will never fire again,
     * which is exactly what the expression asked for.
     */
    private Instant nextRunOrNull(String cronExpr, String timezone) {
        return cronService.nextRun(cronExpr, timezone).orElse(null);
    }

    private JobResponse toResponse(Job job) {
        // describe() re-parses the expression. It is already known valid (nothing invalid is
        // ever persisted), so this cannot throw on a stored job.
        return JobResponse.from(job, cronService.describe(job.getCronExpr()));
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
