INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-00000000001c', 'sandbox_sync',
     'Copy the current project files from the user''s computer to the sandbox''s project directory, so that sandbox_run and desktop_launch work on the latest code. Files ignored by .gitignore, the .git folder and secret-looking files (.env, keys, certificates) are not sent. Files that an earlier sync sent and that are gone on the user''s computer are deleted; files the sandbox created itself, such as installed dependencies and build output, are kept. Edits never flow back: change files with the usual file tools and sync again before the next build or run in the sandbox.',
     '{"type":"object","properties":{},"additionalProperties":false}',
     'execute', 'sandbox_sync', '{agent}', 'ask', 'builtin', now(), now());

UPDATE tools
SET description = 'Run a bash command as root in the Linux sandbox of this session, a container on the Pointer server that also has the display of the desktop tools, and return its exit code and output. The working directory is the sandbox''s project directory, which gets the project files with sandbox_sync. Use it to install system packages with apt-get, build and test there; run_command runs on the user''s computer instead. Avoid interactive commands, and start GUI applications and servers with desktop_launch.',
    updated_at = now()
WHERE id = '01930000-0000-7000-8000-00000000001b';
