ALTER TABLE user_settings
    ADD COLUMN explore_model_id uuid REFERENCES models (id) ON DELETE SET NULL;
