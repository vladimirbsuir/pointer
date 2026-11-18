CREATE TABLE step_signals (
    id          uuid PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id     uuid NOT NULL,
    step        integer NOT NULL,
    model_label text NOT NULL,
    signals     jsonb NOT NULL,
    score       numeric(6, 3) NOT NULL,
    escalated   boolean NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE INDEX step_signals_session_idx ON step_signals (session_id, turn_id, step);
