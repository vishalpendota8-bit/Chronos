package com.chronos.job.dto;

import com.chronos.job.HttpMethodType;
import com.chronos.job.Job;
import com.chronos.job.JobStatus;
import com.chronos.job.MisfirePolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * The public view of a job.
 *
 * @param ownerId taken from the lazy {@code owner} proxy. Reading only the id never triggers a
 *        query — Hibernate already holds it — so listing 50 jobs stays one SELECT rather than
 *        51. Adding {@code ownerEmail} here would reintroduce exactly that N+1.
 * @param cronDescription a human rendering such as "every day at 2:00", so the dashboard does
 *        not have to parse cron in TypeScript.
 * @param nextRunAt null whenever the job is paused, archived, or its cron has no further
 *        occurrence.
 */
public record JobResponse(
        Long id,
        String name,
        Long ownerId,
        String targetUrl,
        HttpMethodType httpMethod,
        Map<String, String> headers,
        String payload,
        String cronExpr,
        String cronDescription,
        String timezone,
        Instant nextRunAt,
        JobStatus status,
        int maxAttempts,
        int initialBackoffSec,
        BigDecimal backoffMultiplier,
        int timeoutSec,
        MisfirePolicy misfirePolicy,
        Instant createdAt
) {
    public static JobResponse from(Job job, String cronDescription) {
        return new JobResponse(
                job.getId(),
                job.getName(),
                job.getOwner().getId(),
                job.getTargetUrl(),
                job.getHttpMethod(),
                job.getHeaders(),
                job.getPayload(),
                job.getCronExpr(),
                cronDescription,
                job.getTimezone(),
                job.getNextRunAt(),
                job.getStatus(),
                job.getMaxAttempts(),
                job.getInitialBackoffSec(),
                job.getBackoffMultiplier(),
                job.getTimeoutSec(),
                job.getMisfirePolicy(),
                job.getCreatedAt());
    }
}
