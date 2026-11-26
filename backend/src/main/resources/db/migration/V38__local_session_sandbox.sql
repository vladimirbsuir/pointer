ALTER TABLE remote_workspaces
    ADD COLUMN kind text NOT NULL DEFAULT 'session' CHECK (kind IN ('session', 'sandbox')),
    ALTER COLUMN repository DROP NOT NULL,
    ALTER COLUMN branch DROP NOT NULL;

ALTER TABLE devices
    ADD COLUMN session_id uuid;

CREATE INDEX devices_session_idx ON devices (session_id) WHERE session_id IS NOT NULL;

UPDATE devices d
SET session_id = w.session_id
FROM remote_workspaces w
WHERE w.device_id = d.id;

ALTER TABLE projects
    ADD COLUMN sandbox_enabled boolean NOT NULL DEFAULT false;

INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-00000000001b', 'sandbox_run',
     'Run a bash command as root in the Linux sandbox of this session, a container on the Pointer server that also has the display of the desktop tools, and return its exit code and output. The working directory is the sandbox''s project directory. Use it to install system packages with apt-get, build and test there; run_command runs on the user''s computer instead. Avoid interactive commands, and start GUI applications and servers with desktop_launch.',
     '{"type":"object","properties":{"command":{"type":"string"},"workingDirectory":{"type":"string","description":"Directory relative to the sandbox''s project directory."},"timeoutMs":{"type":"integer","minimum":1000,"maximum":600000}},"required":["command"],"additionalProperties":false}',
     'execute', 'sandbox_run', '{agent}', 'ask', 'builtin', now(), now());
