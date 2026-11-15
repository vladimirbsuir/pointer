ALTER TABLE user_settings
    ADD COLUMN routing_default text NOT NULL DEFAULT 'off' CHECK (routing_default IN ('off', 'smart')),
    ADD COLUMN fast_model_id uuid REFERENCES models (id) ON DELETE SET NULL,
    ADD COLUMN plan_model_id uuid REFERENCES models (id) ON DELETE SET NULL;

ALTER TABLE sessions
    ADD COLUMN routing text NOT NULL DEFAULT 'off' CHECK (routing IN ('off', 'smart'));
