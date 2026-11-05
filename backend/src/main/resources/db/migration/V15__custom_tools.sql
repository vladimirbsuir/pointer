ALTER TABLE tools ADD COLUMN interpreter text CHECK (interpreter IN ('python', 'node', 'powershell', 'bash'));

CREATE TABLE custom_tool_versions (
    id                uuid PRIMARY KEY,
    tool_id           uuid        NOT NULL REFERENCES tools (id) ON DELETE CASCADE,
    version           integer     NOT NULL,
    description       text        NOT NULL,
    parameters_schema jsonb       NOT NULL,
    interpreter       text        NOT NULL CHECK (interpreter IN ('python', 'node', 'powershell', 'bash')),
    body              text        NOT NULL,
    session_id        uuid REFERENCES sessions (id) ON DELETE SET NULL,
    item_id           uuid REFERENCES items (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL,
    UNIQUE (tool_id, version)
);
