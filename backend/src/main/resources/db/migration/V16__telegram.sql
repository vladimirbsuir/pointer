CREATE TABLE telegram_links (
    id                  uuid PRIMARY KEY,
    user_id             uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    chat_id             bigint      NOT NULL UNIQUE,
    telegram_username   text,
    selected_session_id uuid REFERENCES sessions (id) ON DELETE SET NULL,
    linked_at           timestamptz NOT NULL
);

CREATE TABLE telegram_link_codes (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    code_hash  text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL
);
