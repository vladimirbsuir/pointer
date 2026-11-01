CREATE TABLE tools (
    id                uuid PRIMARY KEY,
    name              text        NOT NULL,
    description       text        NOT NULL,
    parameters_schema jsonb       NOT NULL,
    kind              text        NOT NULL CHECK (kind IN ('read', 'write', 'execute')),
    operation         text        NOT NULL,
    modes             text[]      NOT NULL,
    default_policy    text        NOT NULL CHECK (default_policy IN ('allow', 'ask', 'deny')),
    source            text        NOT NULL CHECK (source IN ('builtin', 'custom')),
    owner_user_id     uuid REFERENCES users (id) ON DELETE CASCADE,
    project_id        uuid REFERENCES projects (id) ON DELETE CASCADE,
    version           integer     NOT NULL DEFAULT 1,
    enabled           boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    UNIQUE NULLS NOT DISTINCT (name, owner_user_id, project_id)
);

CREATE TABLE permission_policies (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id uuid REFERENCES projects (id) ON DELETE CASCADE,
    tool_id    uuid        NOT NULL REFERENCES tools (id) ON DELETE CASCADE,
    policy     text        NOT NULL CHECK (policy IN ('allow', 'ask', 'deny')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE NULLS NOT DISTINCT (user_id, project_id, tool_id)
);

CREATE TABLE command_rules (
    id         uuid PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id uuid REFERENCES projects (id) ON DELETE CASCADE,
    prefix     text        NOT NULL,
    policy     text        NOT NULL CHECK (policy IN ('allow', 'ask', 'deny')),
    created_at timestamptz NOT NULL
);

CREATE INDEX command_rules_user_idx ON command_rules (user_id, project_id);

CREATE TABLE approvals (
    id          uuid PRIMARY KEY,
    session_id  uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    item_id     uuid        NOT NULL UNIQUE REFERENCES items (id) ON DELETE CASCADE,
    preview     jsonb       NOT NULL,
    status      text        NOT NULL CHECK (status IN ('pending', 'approved', 'denied', 'cancelled')),
    scope       text CHECK (scope IN ('once', 'session')),
    message     text,
    created_at  timestamptz NOT NULL,
    resolved_at timestamptz
);

CREATE TABLE session_grants (
    id             uuid PRIMARY KEY,
    session_id     uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    tool_id        uuid        NOT NULL REFERENCES tools (id) ON DELETE CASCADE,
    command_prefix text,
    created_at     timestamptz NOT NULL
);

CREATE INDEX session_grants_session_idx ON session_grants (session_id);

CREATE TABLE file_changes (
    id             uuid PRIMARY KEY,
    session_id     uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id        uuid        NOT NULL REFERENCES turns (id) ON DELETE CASCADE,
    item_id        uuid        NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    path           text        NOT NULL,
    change_kind    text        NOT NULL CHECK (change_kind IN ('created', 'modified', 'deleted')),
    before_content text,
    after_content  text,
    lines_added    integer     NOT NULL DEFAULT 0,
    lines_removed  integer     NOT NULL DEFAULT 0,
    reverted_at    timestamptz,
    created_at     timestamptz NOT NULL
);

CREATE INDEX file_changes_session_path_idx ON file_changes (session_id, path);

INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-000000000001', 'list_directory',
     'List files and folders in a directory of the project. Paths are relative to the project root.',
     '{"type":"object","properties":{"path":{"type":"string","description":"Directory relative to the project root. Use \".\" for the root."}},"required":["path"],"additionalProperties":false}',
     'read', 'list_directory', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-000000000002', 'read_file',
     'Read a text file of the project. Large files can be read in parts with offset and limit (line numbers, 1-based).',
     '{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer","minimum":1},"limit":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}',
     'read', 'read_file', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-000000000003', 'glob',
     'Find files by glob pattern such as "src/**/*.ts". Respects .gitignore.',
     '{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string","description":"Directory to search in, defaults to the project root."}},"required":["pattern"],"additionalProperties":false}',
     'read', 'glob', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-000000000004', 'grep',
     'Search file contents with a regular expression. Respects .gitignore and skips binary files.',
     '{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"},"glob":{"type":"string","description":"Only search files matching this glob."},"caseInsensitive":{"type":"boolean"},"maxResults":{"type":"integer","minimum":1,"maximum":500}},"required":["pattern"],"additionalProperties":false}',
     'read', 'grep', '{ask,plan,agent}', 'allow', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-000000000005', 'write_file',
     'Create a file or overwrite it completely. Prefer edit_file for changes to existing files.',
     '{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}',
     'write', 'write_file', '{agent}', 'ask', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-000000000006', 'edit_file',
     'Replace an exact fragment of a file. oldText must match the file exactly, including whitespace, and be unique unless replaceAll is true. Read the file first.',
     '{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"replaceAll":{"type":"boolean"}},"required":["path","oldText","newText"],"additionalProperties":false}',
     'write', 'edit_file', '{agent}', 'ask', 'builtin', now(), now()),
    ('01930000-0000-7000-8000-000000000007', 'run_command',
     'Run a PowerShell command in the project directory and return its exit code and output. Avoid interactive commands.',
     '{"type":"object","properties":{"command":{"type":"string"},"workingDirectory":{"type":"string","description":"Directory relative to the project root."},"timeoutMs":{"type":"integer","minimum":1000,"maximum":600000}},"required":["command"],"additionalProperties":false}',
     'execute', 'run_command', '{agent}', 'ask', 'builtin', now(), now());
