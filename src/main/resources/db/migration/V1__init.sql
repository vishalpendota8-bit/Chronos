-- Chronos initial schema.
-- Timestamps are all `timestamptz`: Postgres stores them as UTC instants, which maps
-- cleanly to java.time.Instant. Wall-clock/timezone logic lives only in cron evaluation.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Emails are compared case-insensitively, so the uniqueness guarantee must be too.
CREATE UNIQUE INDEX ux_users_email_lower ON users (LOWER(email));


CREATE TABLE jobs (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(120)  NOT NULL,
    owner_id           BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_url         VARCHAR(2048) NOT NULL,
    http_method        VARCHAR(10)   NOT NULL,
    headers_json       JSONB,
    payload_json       JSONB,
    cron_expr          VARCHAR(200)  NOT NULL,
    timezone           VARCHAR(64)   NOT NULL DEFAULT 'UTC',
    next_run_at        TIMESTAMPTZ,
    status             VARCHAR(16)   NOT NULL,
    max_attempts       INT           NOT NULL DEFAULT 3,
    initial_backoff_sec INT          NOT NULL DEFAULT 10,
    backoff_multiplier NUMERIC(5, 2) NOT NULL DEFAULT 2.0,
    timeout_sec        INT           NOT NULL DEFAULT 30,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_jobs_status       CHECK (status IN ('ENABLED', 'PAUSED', 'ARCHIVED')),
    CONSTRAINT ck_jobs_max_attempts CHECK (max_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_jobs_backoff      CHECK (initial_backoff_sec >= 1 AND backoff_multiplier >= 1.0),
    CONSTRAINT ck_jobs_timeout      CHECK (timeout_sec BETWEEN 1 AND 300),
    CONSTRAINT ux_jobs_owner_name   UNIQUE (owner_id, name)
);

CREATE INDEX ix_jobs_owner ON jobs (owner_id);


CREATE TABLE job_executions (
    id               BIGSERIAL PRIMARY KEY,
    job_id           BIGINT      NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    attempt_no       INT         NOT NULL DEFAULT 1,
    -- scheduled_for = the cron occurrence this row belongs to (stable across retries).
    -- run_at        = when this specific attempt becomes eligible (moves on each retry).
    scheduled_for    TIMESTAMPTZ NOT NULL,
    run_at           TIMESTAMPTZ NOT NULL,
    claimed_at       TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    status           VARCHAR(20) NOT NULL,
    response_code    INT,
    response_snippet TEXT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_exec_status CHECK (status IN
        ('QUEUED', 'RETRY_SCHEDULED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD'))
);

-- The safety net for a multi-node scheduler: if two nodes both finish the same execution
-- and both try to enqueue the next cron occurrence, the second INSERT fails instead of
-- silently creating a duplicate run.
CREATE UNIQUE INDEX ux_exec_job_occurrence_attempt
    ON job_executions (job_id, scheduled_for, attempt_no);

-- The poller's hot query is "due and not yet finished, oldest first". A partial index keeps
-- it small: finished rows (the vast majority over time) are never indexed here.
CREATE INDEX ix_exec_due
    ON job_executions (run_at)
    WHERE status IN ('QUEUED', 'RETRY_SCHEDULED');

-- The reaper's query: RUNNING rows that started too long ago.
CREATE INDEX ix_exec_running
    ON job_executions (started_at)
    WHERE status = 'RUNNING';

CREATE INDEX ix_exec_job_history ON job_executions (job_id, scheduled_for DESC);


CREATE TABLE dead_letters (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT      NOT NULL REFERENCES job_executions (id) ON DELETE CASCADE,
    job_id       BIGINT      NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    reason       TEXT        NOT NULL,
    failed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at  TIMESTAMPTZ,

    -- One execution can only die once.
    CONSTRAINT ux_dead_letters_execution UNIQUE (execution_id)
);

CREATE INDEX ix_dead_letters_job ON dead_letters (job_id, failed_at DESC);
-- Partial index for the default dashboard view: "not yet replayed".
CREATE INDEX ix_dead_letters_pending ON dead_letters (failed_at DESC) WHERE replayed_at IS NULL;
