CREATE TABLE providers (
    id                uuid PRIMARY KEY,
    user_id           uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name              text        NOT NULL,
    adapter           text        NOT NULL DEFAULT 'openai_compatible' CHECK (adapter IN ('openai_compatible')),
    base_url          text        NOT NULL,
    api_key_encrypted bytea,
    api_key_hint      text,
    is_local          boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE INDEX providers_user_id_idx ON providers (user_id);

CREATE TABLE models (
    id                   uuid PRIMARY KEY,
    provider_id          uuid        NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    model_key            text        NOT NULL,
    display_name         text        NOT NULL,
    context_window       integer,
    max_output_tokens    integer,
    supports_tools       boolean     NOT NULL DEFAULT true,
    supports_vision      boolean     NOT NULL DEFAULT false,
    input_price_per_mtok numeric(12, 6),
    output_price_per_mtok numeric(12, 6),
    source               text        NOT NULL CHECK (source IN ('discovered', 'manual')),
    enabled              boolean     NOT NULL DEFAULT true,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    UNIQUE (provider_id, model_key)
);

ALTER TABLE user_settings
    ADD COLUMN default_model_id uuid REFERENCES models (id) ON DELETE SET NULL;
