ALTER TABLE turns ADD COLUMN kind text NOT NULL DEFAULT 'message' CHECK (kind IN ('message', 'compaction'));
