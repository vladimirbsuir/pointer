ALTER TABLE user_settings
    ADD COLUMN embedding_model_id uuid REFERENCES models (id) ON DELETE SET NULL;

CREATE TABLE index_states (
    project_id          uuid PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    user_id             uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    embedding_model_id  uuid REFERENCES models (id) ON DELETE SET NULL,
    embedding_model_key text,
    dimensions          integer,
    status              text        NOT NULL CHECK (status IN ('indexing', 'ready', 'failed')),
    files_total         integer     NOT NULL DEFAULT 0,
    files_done          integer     NOT NULL DEFAULT 0,
    file_count          integer     NOT NULL DEFAULT 0,
    chunk_count         integer     NOT NULL DEFAULT 0,
    error               text,
    indexed_at          timestamptz,
    updated_at          timestamptz NOT NULL
);

CREATE TABLE indexed_files (
    project_id   uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    path         text NOT NULL,
    content_hash text NOT NULL,
    PRIMARY KEY (project_id, path)
);

CREATE TABLE code_chunks (
    id         uuid PRIMARY KEY,
    project_id uuid    NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    path       text    NOT NULL,
    symbol     text,
    kind       text    NOT NULL,
    start_line integer NOT NULL,
    end_line   integer NOT NULL,
    content    text    NOT NULL,
    embedding  vector  NOT NULL
);

CREATE INDEX code_chunks_project_path_idx ON code_chunks (project_id, path);

INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-000000000009', 'semantic_search',
     'Search the project''s code by meaning and return the most relevant fragments with paths and line ranges. Use it to find where a behaviour or concept is implemented when you do not know the exact names; use grep for exact text. Results reflect the project folder.',
     '{"type":"object","properties":{"query":{"type":"string","description":"What to look for, in natural language or code terms."},"limit":{"type":"integer","minimum":1,"maximum":20,"description":"Number of fragments to return, 8 by default."},"path":{"type":"string","description":"Only search files under this directory, relative to the project root."}},"required":["query"],"additionalProperties":false}',
     'read', 'semantic_search', '{ask,plan,agent}', 'allow', 'builtin', now(), now());
