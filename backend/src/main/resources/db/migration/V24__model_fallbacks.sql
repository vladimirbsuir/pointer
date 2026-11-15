CREATE TABLE model_fallbacks (
    model_id          uuid    NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    position          integer NOT NULL,
    fallback_model_id uuid    NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    PRIMARY KEY (model_id, position),
    UNIQUE (model_id, fallback_model_id),
    CHECK (model_id <> fallback_model_id)
);

ALTER TABLE items DROP CONSTRAINT items_type_check;
ALTER TABLE items ADD CONSTRAINT items_type_check
    CHECK (type IN ('user_message', 'assistant_message', 'reasoning', 'tool_call', 'plan', 'compaction_summary',
                    'model_switched'));
