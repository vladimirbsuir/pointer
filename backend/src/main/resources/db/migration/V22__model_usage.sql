ALTER TABLE sessions ADD COLUMN budget_usd numeric(10, 4);

ALTER TABLE turns DROP CONSTRAINT turns_status_check;
ALTER TABLE turns ADD CONSTRAINT turns_status_check
    CHECK (status IN ('running', 'completed', 'cancelled', 'failed', 'step_limit', 'interrupted', 'budget_exceeded'));

CREATE TABLE model_usage (
    id                uuid PRIMARY KEY,
    session_id        uuid           NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id           uuid REFERENCES turns (id) ON DELETE SET NULL,
    model_id          uuid REFERENCES models (id) ON DELETE SET NULL,
    model_label       text           NOT NULL,
    role              text           NOT NULL CHECK (role IN ('main', 'plan', 'fast', 'explore', 'fallback')),
    purpose           text           NOT NULL CHECK (purpose IN ('step', 'title', 'compaction', 'memory', 'explore')),
    prompt_tokens     bigint         NOT NULL,
    completion_tokens bigint         NOT NULL,
    cost_usd          numeric(12, 6),
    created_at        timestamptz    NOT NULL
);

CREATE INDEX model_usage_session_idx ON model_usage (session_id, created_at);
