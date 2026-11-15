CREATE TABLE memories (
    id                 uuid PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id         uuid REFERENCES projects (id) ON DELETE CASCADE,
    kind               text        NOT NULL CHECK (kind IN ('user', 'feedback', 'project', 'reference')),
    name               text        NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
    description        text        NOT NULL,
    content            text        NOT NULL,
    status             text        NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suggested', 'dismissed', 'deleted')),
    pinned             boolean     NOT NULL DEFAULT false,
    source             text        NOT NULL CHECK (source IN ('agent', 'user', 'extracted', 'telegram')),
    source_session_id  uuid REFERENCES sessions (id) ON DELETE SET NULL,
    embedding          vector,
    embedding_model_id uuid REFERENCES models (id) ON DELETE SET NULL,
    read_count         integer     NOT NULL DEFAULT 0,
    last_read_at       timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);

CREATE UNIQUE INDEX memories_scope_name_idx
    ON memories (user_id, coalesce(project_id, '00000000-0000-0000-0000-000000000000'::uuid), name)
    WHERE status IN ('active', 'suggested');

CREATE INDEX memories_user_project_idx ON memories (user_id, project_id, status);

CREATE TABLE memory_revisions (
    id          uuid PRIMARY KEY,
    memory_id   uuid        NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    kind        text,
    description text,
    content     text,
    status      text,
    pinned      boolean,
    created_at  timestamptz NOT NULL
);

CREATE INDEX memory_revisions_memory_idx ON memory_revisions (memory_id, created_at DESC);

INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-00000000000a', 'memory_save',
     'Save a memory for later sessions, or update the memory with the same name in that scope. Save when the user states a preference or corrects you, or when you learn a non-obvious fact about the project that is not recorded in the code or the project rules. Keep one fact per memory. Scope user applies to every project, scope project only to this one. Kinds: user (who the user is), feedback (how the user wants you to work; add Why and How to apply lines), project (ongoing work, decisions, constraints), reference (where to find external information).',
     '{"type":"object","properties":{"scope":{"type":"string","enum":["user","project"]},"kind":{"type":"string","enum":["user","feedback","project","reference"]},"name":{"type":"string","pattern":"^[a-z0-9][a-z0-9-]{1,63}$","description":"Short kebab-case name, unique in the scope."},"description":{"type":"string","maxLength":200,"description":"One line used to decide relevance."},"content":{"type":"string","maxLength":4000,"description":"The fact in Markdown."}},"required":["scope","kind","name","description","content"],"additionalProperties":false}',
     'read', 'memory_save', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-00000000000b', 'memory_read',
     'Read the full content of memories by name. Memories listed in the system prompt show only their descriptions.',
     '{"type":"object","properties":{"names":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":10}},"required":["names"],"additionalProperties":false}',
     'read', 'memory_read', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-00000000000c', 'memory_search',
     'Search all memories of the user and this project by meaning and words. Use it when the memory index in the system prompt is cut short or does not show what you need.',
     '{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":20}},"required":["query"],"additionalProperties":false}',
     'read', 'memory_search', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-00000000000d', 'memory_delete',
     'Delete a memory that turned out to be wrong or outdated. The user can undo it.',
     '{"type":"object","properties":{"scope":{"type":"string","enum":["user","project"]},"name":{"type":"string"}},"required":["scope","name"],"additionalProperties":false}',
     'read', 'memory_delete', '{ask,plan,agent}', 'allow', 'builtin', now(), now());
