ALTER TABLE user_settings
    ADD COLUMN escalation_default text NOT NULL DEFAULT 'off' CHECK (escalation_default IN ('off', 'on'));

ALTER TABLE sessions
    ADD COLUMN escalation text NOT NULL DEFAULT 'off' CHECK (escalation IN ('off', 'on'));

ALTER TABLE items DROP CONSTRAINT items_type_check;
ALTER TABLE items ADD CONSTRAINT items_type_check
    CHECK (type IN ('user_message', 'assistant_message', 'reasoning', 'tool_call', 'plan', 'compaction_summary',
                    'model_switched', 'model_escalated'));
