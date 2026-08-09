package com.chronos.scheduler;

import com.chronos.config.SchedulerProperties;
import com.chronos.job.Job;
import com.chronos.job.JobRepository;
import com.chronos.job.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Sweeps jobs whose {@code next_run_at} has arrived and turns each into an execution row.
 *
 * <p><b>Where this fits:</b> M3 stores "when should this run next" on the job, but nothing has
 * yet created anything for the poller to claim. This sweep is that bridge, and it is what
 * actually starts a schedule.
 *
 * <p><b>Design note — a sweep, not an enqueue-on-success callback.</b> The obvious alternative
 * is to insert the next occurrence when the previous one succeeds. A timer-driven sweep over
 * {@code next_run_at} was chosen instead because it also handles the two cases a completion
 * callback cannot: the <em>first</em> run of a brand-new job, and restarting after the whole
 * cluster was down (nothing completed, so no callback would ever fire). The overlap guard in
 * {@link JobOccurrenceEnqueuer} preserves the one property the callback design had for free —
 * never running two attempts of the same job at once.
 *
 * <p>Deliberately not transactional: each job commits separately, inside
 * {@link JobOccurrenceEnqueuer#enqueueOne}.
 */
@Service
public class ExecutionEnqueuer {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEnqueuer.class);

    private final JobRepository jobs;
    private final JobOccurrenceEnqueuer occurrenceEnqueuer;
    private final SchedulerProperties properties;
    private final InFlightRegistry registry;

    public ExecutionEnqueuer(JobRepository jobs, JobOccurrenceEnqueuer occurrenceEnqueuer,
                             SchedulerProperties properties, InFlightRegistry registry) {
        this.jobs = jobs;
        this.occurrenceEnqueuer = occurrenceEnqueuer;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * One sweep: every ENABLED job whose next occurrence is due gets an execution row.
     *
     * @return how many executions were created — used by tests and logging.
     */
    public int enqueueDueJobs() {
        // A shutting-down node stops sweeping too. Not for safety — an enqueued row is claimable
        // by any node, so nothing would be stranded — but because advancing next_run_at on the
        // way out is pointless work against a database the node is about to disconnect from.
        if (!registry.isAccepting()) {
            return 0;
        }

        List<Job> due = jobs.findByStatusAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
                JobStatus.ENABLED, Instant.now(),
                PageRequest.of(0, properties.enqueueBatchSize()));

        int created = 0;
        for (Job job : due) {
            try {
                if (occurrenceEnqueuer.enqueueOne(job.getId())) {
                    created++;
                }
            } catch (RuntimeException e) {
                // One job with a broken schedule must not stop the rest of the sweep.
                log.error("Failed to enqueue job {}", job.getId(), e);
            }
        }

        if (created > 0) {
            log.debug("Enqueued {} execution(s) from {} due job(s)", created, due.size());
        }
        return created;
    }
}
