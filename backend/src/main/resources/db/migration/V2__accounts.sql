CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         citext      NOT NULL UNIQUE,
    password_hash text,
    display_name  text        NOT NULL,
    locale        text        NOT NULL DEFAULT 'en' CHECK (locale IN ('en', 'ru')),
    disabled_at   timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);

CREATE TABLE user_identities (
    id                     uuid PRIMARY KEY,
    user_id                uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider               text        NOT NULL CHECK (provider IN ('github')),
    provider_user_id       text        NOT NULL,
    provider_login         text        NOT NULL,
    access_token_encrypted bytea,
    scopes                 text,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    UNIQUE (provider, provider_user_id),
    UNIQUE (user_id, provider)
);

CREATE TABLE devices (
    id             uuid PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name           text        NOT NULL,
    platform       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    client_version text,
    last_seen_at   timestamptz NOT NULL,
    revoked_at     timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);

CREATE INDEX devices_user_id_idx ON devices (user_id);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id  uuid REFERENCES devices (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash text        NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    rotated_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);

CREATE TABLE user_settings (
    user_id             uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    global_instructions text        NOT NULL DEFAULT '',
    preferences         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at          timestamptz NOT NULL
);
