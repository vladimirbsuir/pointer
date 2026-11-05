ALTER TABLE devices
    ADD COLUMN kind               text NOT NULL DEFAULT 'desktop',
    ADD COLUMN runner_secret_hash text;

ALTER TABLE projects
    ADD COLUMN repository text;

CREATE TABLE remote_workspaces (
    id               uuid PRIMARY KEY,
    session_id       uuid        NOT NULL UNIQUE REFERENCES sessions (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id        uuid        NOT NULL REFERENCES devices (id),
    repository       text        NOT NULL,
    branch           text        NOT NULL,
    base_branch      text,
    container_name   text        NOT NULL UNIQUE,
    volume_name      text        NOT NULL UNIQUE,
    status           text        NOT NULL,
    error            text,
    pushed_commit    text,
    pull_request_url text,
    last_active_at   timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

CREATE INDEX remote_workspaces_status_idx ON remote_workspaces (status, last_active_at);
