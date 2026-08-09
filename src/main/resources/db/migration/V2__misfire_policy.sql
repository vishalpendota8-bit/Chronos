-- M6: misfire policy on jobs, plus the index the stats time-window query needs.
--
-- Written as a new migration rather than an edit to V1__init.sql. Flyway records a checksum of
-- every file it has applied; changing an applied migration makes it refuse to start ("migration
-- checksum mismatch"). Forward-only migrations are the rule, even in development.

-- What should happen when an occurrence is enqueued long after it was due — because the whole
-- cluster was down, or because the previous run overran its own period.
--
--   FIRE_NOW  run the late occurrence anyway (the M4/M5 behaviour, so it is the default)
--   SKIP      abandon it and jump the schedule forward to the next future occurrence
--
-- DEFAULT + NOT NULL together mean existing rows are backfilled by this statement; Postgres 11+
-- does that without rewriting the table, so this is safe on a large jobs table.
ALTER TABLE jobs
    ADD COLUMN misfire_policy VARCHAR(16) NOT NULL DEFAULT 'FIRE_NOW';

ALTER TABLE jobs
    ADD CONSTRAINT ck_jobs_misfire_policy CHECK (misfire_policy IN ('FIRE_NOW', 'SKIP'));


-- The stats endpoint counts executions in a time window across all jobs. V1 indexed
-- (job_id, scheduled_for) which serves per-job history, but a leading job_id is useless to a
-- query that spans every job — so this adds the cross-job ordering.
CREATE INDEX ix_exec_scheduled_for ON job_executions (scheduled_for DESC);

-- Deliberately NOT added: an index for the reaper's "RUNNING for too long" query. The set of
-- RUNNING rows is bounded by how many dispatches the whole cluster can have in flight at once
-- (nodes x max-pool-size — a few dozen), so V1's ix_exec_running partial index already narrows
-- it to a handful of rows and the per-row timeout arithmetic is evaluated on those. An index on
-- the timeout expression itself could not help anyway: the cutoff depends on each job's own
-- timeout_sec, so there is no single sortable value to index.
