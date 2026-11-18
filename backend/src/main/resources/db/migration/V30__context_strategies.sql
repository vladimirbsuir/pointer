ALTER TABLE user_settings
    ADD COLUMN context_strategy_default text NOT NULL DEFAULT 'summary'
        CHECK (context_strategy_default IN ('summary', 'masking', 'hybrid'));

ALTER TABLE sessions
    ADD COLUMN context_strategy text NOT NULL DEFAULT 'summary'
        CHECK (context_strategy IN ('summary', 'masking', 'hybrid'));
