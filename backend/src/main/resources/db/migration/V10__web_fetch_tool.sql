INSERT INTO tools (id, name, description, parameters_schema, kind, operation, modes, default_policy, source, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-000000000008', 'web_fetch',
     'Fetch an http or https URL from the user''s computer and return its content as readable text. HTML is converted to text. Use it for documentation pages, issues and local dev servers.',
     '{"type":"object","properties":{"url":{"type":"string","description":"Absolute http or https URL."},"maxChars":{"type":"integer","minimum":1,"maximum":25000,"description":"Maximum characters to return, 20000 by default."}},"required":["url"],"additionalProperties":false}',
     'read', 'web_fetch', '{ask,plan,agent}', 'ask', 'builtin', now(), now());
