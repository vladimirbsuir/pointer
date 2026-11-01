ALTER TABLE tools DROP CONSTRAINT tools_source_check;
ALTER TABLE tools ADD CONSTRAINT tools_source_check CHECK (source IN ('builtin', 'custom', 'mcp'));
ALTER TABLE tools ADD COLUMN mcp_server text;
ALTER TABLE tools ADD COLUMN mcp_tool text;
