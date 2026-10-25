-- +goose Up
CREATE TABLE plans (
    code   text PRIMARY KEY,
    limits jsonb NOT NULL
);

-- Limits are data, so paid tiers can be added or tuned without code changes.
INSERT INTO plans (code, limits) VALUES
    ('anon', '{"tasks_per_hour": 10,   "max_file_mb": 50,  "max_files_per_task": 20,  "storage_mb": 0}'),
    ('free', '{"tasks_per_hour": 100,  "max_file_mb": 200, "max_files_per_task": 50,  "storage_mb": 1024}'),
    ('pro',  '{"tasks_per_hour": 1000, "max_file_mb": 500, "max_files_per_task": 200, "storage_mb": 10240}');

CREATE TABLE users (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email          text NOT NULL UNIQUE,
    password_hash  text,
    google_sub     text UNIQUE,
    name           text NOT NULL DEFAULT '',
    avatar_url     text NOT NULL DEFAULT '',
    plan           text NOT NULL DEFAULT 'free' REFERENCES plans (code),
    email_verified boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,
    device_id  text NOT NULL DEFAULT '',
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- One-time tokens for email verification and password reset.
CREATE TABLE email_tokens (
    token_hash text PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    text NOT NULL CHECK (purpose IN ('verify', 'reset')),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);

CREATE TABLE jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid REFERENCES users (id) ON DELETE CASCADE,
    anon_key        text,
    tool            text NOT NULL,
    options         jsonb NOT NULL DEFAULT '{}',
    status          text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'processing', 'done', 'failed')),
    progress        int NOT NULL DEFAULT 0,
    error_code      text,
    error_message   text,
    input_ids       uuid[] NOT NULL,
    output_id       uuid,
    idempotency_key text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    started_at      timestamptz,
    finished_at     timestamptz,
    CHECK (user_id IS NOT NULL OR anon_key IS NOT NULL)
);
CREATE INDEX jobs_user_idx ON jobs (user_id, created_at DESC);
CREATE UNIQUE INDEX jobs_idempotency_idx ON jobs (COALESCE(user_id::text, anon_key), idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE files (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid REFERENCES users (id) ON DELETE CASCADE,
    anon_key   text,
    job_id     uuid REFERENCES jobs (id) ON DELETE SET NULL, -- set for outputs
    kind       text NOT NULL CHECK (kind IN ('input', 'output')),
    name       text NOT NULL,
    size       bigint NOT NULL,
    mime       text NOT NULL,
    s3_key     text NOT NULL,
    expires_at timestamptz, -- NULL = kept in the user's history
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (user_id IS NOT NULL OR anon_key IS NOT NULL)
);
CREATE INDEX files_user_idx ON files (user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX files_expiry_idx ON files (expires_at) WHERE deleted_at IS NULL AND expires_at IS NOT NULL;

-- Per-subject usage counters; the basis for future billing.
CREATE TABLE usage_daily (
    subject text NOT NULL,
    day     date NOT NULL,
    tasks   int NOT NULL DEFAULT 0,
    bytes   bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (subject, day)
);

-- +goose Down
DROP TABLE usage_daily;
DROP TABLE files;
DROP TABLE jobs;
DROP TABLE email_tokens;
DROP TABLE refresh_tokens;
DROP TABLE users;
DROP TABLE plans;
