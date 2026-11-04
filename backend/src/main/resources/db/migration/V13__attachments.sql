CREATE TABLE attachments (
    id          uuid PRIMARY KEY,
    session_id  uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    media_type  text        NOT NULL,
    size_bytes  integer     NOT NULL,
    data        bytea       NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE INDEX attachments_session_idx ON attachments (session_id);
