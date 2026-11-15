CREATE TABLE automations (
    id                       uuid PRIMARY KEY,
    user_id                  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id               uuid        NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    enabled                  boolean     NOT NULL DEFAULT true,
    trigger_label            text        NOT NULL DEFAULT 'pointer',
    base_branch              text,
    model_id                 uuid REFERENCES models (id) ON DELETE SET NULL,
    check_command            text,
    max_attempts             integer     NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    budget_usd               numeric(10, 4),
    webhook_id               bigint,
    webhook_secret_encrypted bytea       NOT NULL,
    created_at               timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL
);

CREATE TABLE automation_runs (
    id                uuid PRIMARY KEY,
    automation_id     uuid           NOT NULL REFERENCES automations (id) ON DELETE CASCADE,
    session_id        uuid REFERENCES sessions (id) ON DELETE SET NULL,
    issue_number      integer        NOT NULL,
    issue_title       text           NOT NULL,
    trigger           text           NOT NULL CHECK (trigger IN ('label', 'desktop', 'telegram')),
    status            text           NOT NULL CHECK (status IN ('queued', 'running', 'checking', 'succeeded', 'failed', 'cancelled')),
    failure_reason    text,
    error             text,
    attempts          integer        NOT NULL DEFAULT 0,
    last_check_output text,
    pull_request_url  text,
    status_comment_id bigint,
    cost_usd          numeric(12, 6) NOT NULL DEFAULT 0,
    started_at        timestamptz    NOT NULL,
    finished_at       timestamptz
);

CREATE UNIQUE INDEX automation_runs_active_issue_idx
    ON automation_runs (automation_id, issue_number)
    WHERE status IN ('queued', 'running', 'checking');

CREATE INDEX automation_runs_automation_idx ON automation_runs (automation_id, started_at DESC);

CREATE INDEX automation_runs_session_idx ON automation_runs (session_id);
