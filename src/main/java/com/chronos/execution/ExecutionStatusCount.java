package com.chronos.execution;

/**
 * One row of a {@code GROUP BY status} over {@code job_executions}.
 *
 * <p>Lives next to the repository that produces it rather than in the stats package, because the
 * JPQL constructor expression names this class by its fully-qualified name — the query and the
 * record have to be changed together, so keeping them adjacent is the honest arrangement.
 *
 * @param count boxed {@code Long}, not {@code long}: Hibernate resolves the constructor by
 *        matching argument types, and {@code COUNT()} produces a {@code Long}. A primitive
 *        component would rely on the resolver boxing for us, which is exactly the kind of thing
 *        that works until a version bump.
 */
public record ExecutionStatusCount(ExecutionStatus status, Long count) {
}
