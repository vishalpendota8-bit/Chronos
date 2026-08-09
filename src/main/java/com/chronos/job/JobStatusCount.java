package com.chronos.job;

/**
 * One row of a {@code GROUP BY status} over {@code jobs}.
 *
 * <p>See {@link com.chronos.execution.ExecutionStatusCount} for why {@code count} is boxed and
 * why this record sits beside its repository.
 */
public record JobStatusCount(JobStatus status, Long count) {
}
