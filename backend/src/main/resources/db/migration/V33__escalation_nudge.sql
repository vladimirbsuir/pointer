ALTER TABLE step_signals
    ADD COLUMN nudged boolean NOT NULL DEFAULT false;

ALTER TABLE items DROP CONSTRAINT items_type_check;
ALTER TABLE items ADD CONSTRAINT items_type_check
    CHECK (type IN ('user_message', 'assistant_message', 'reasoning', 'tool_call', 'plan', 'compaction_summary',
                    'model_switched', 'model_escalated', 'model_nudged'));
