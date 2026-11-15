CREATE TABLE session_digests (
    session_id         uuid PRIMARY KEY REFERENCES sessions (id) ON DELETE CASCADE,
    user_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id         uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    summary            text,
    processed_seq      bigint      NOT NULL DEFAULT 0,
    attempts           integer     NOT NULL DEFAULT 0,
    suggested_count    integer     NOT NULL DEFAULT 0,
    embedding          vector,
    embedding_model_id uuid REFERENCES models (id) ON DELETE SET NULL,
    updated_at         timestamptz NOT NULL
);

CREATE INDEX session_digests_project_idx ON session_digests (user_id, project_id);

INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-00000000000e', 'search_sessions',
     'Search summaries of earlier sessions in this project: what was asked, what was done, which files changed and what was decided. Use it when the user refers to earlier work ("like last time", "the bug we fixed") or when past decisions matter.',
     '{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":10}},"required":["query"],"additionalProperties":false}',
     'read', 'search_sessions', '{ask,plan,agent}', 'allow', 'builtin', now(), now());
