CREATE TABLE projects (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        text        NOT NULL,
    archived_at timestamptz,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX projects_user_id_idx ON projects (user_id);

CREATE TABLE sessions (
    id                  uuid PRIMARY KEY,
    user_id             uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id          uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    executor_device_id  uuid        REFERENCES devices (id) ON DELETE SET NULL,
    title               text        NOT NULL,
    title_generated     boolean     NOT NULL DEFAULT true,
    mode                text        NOT NULL CHECK (mode IN ('ask', 'plan', 'agent')),
    model_id            uuid        REFERENCES models (id) ON DELETE SET NULL,
    status              text        NOT NULL CHECK (status IN ('idle', 'running', 'waiting_for_approval', 'waiting_for_executor', 'error')),
    auto_approve        boolean     NOT NULL DEFAULT false,
    last_seq            bigint      NOT NULL DEFAULT 0,
    last_position       bigint      NOT NULL DEFAULT 0,
    context_tokens      integer     NOT NULL DEFAULT 0,
    total_input_tokens  bigint      NOT NULL DEFAULT 0,
    total_output_tokens bigint      NOT NULL DEFAULT 0,
    archived_at         timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

CREATE INDEX sessions_project_updated_idx ON sessions (project_id, updated_at DESC);

CREATE TABLE turns (
    id              uuid PRIMARY KEY,
    session_id      uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    number          integer     NOT NULL,
    mode            text        NOT NULL,
    model_id        uuid        REFERENCES models (id) ON DELETE SET NULL,
    status          text        NOT NULL CHECK (status IN ('running', 'completed', 'cancelled', 'failed', 'step_limit', 'interrupted')),
    error           jsonb,
    input_tokens    bigint      NOT NULL DEFAULT 0,
    output_tokens   bigint      NOT NULL DEFAULT 0,
    idempotency_key text,
    started_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    UNIQUE (session_id, number),
    UNIQUE (session_id, idempotency_key)
);

CREATE TABLE items (
    id           uuid PRIMARY KEY,
    session_id   uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id      uuid        REFERENCES turns (id) ON DELETE CASCADE,
    position     bigint      NOT NULL,
    type         text        NOT NULL CHECK (type IN ('user_message', 'assistant_message', 'reasoning', 'tool_call', 'plan', 'compaction_summary')),
    status       text        NOT NULL CHECK (status IN ('in_progress', 'completed', 'failed', 'cancelled')),
    payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE (session_id, position)
);

CREATE TABLE session_events (
    session_id  uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    seq         bigint      NOT NULL,
    kind        text        NOT NULL,
    payload     jsonb       NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (session_id, seq)
);
