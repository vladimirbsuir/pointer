ALTER TABLE models
    ADD COLUMN cached_input_price_per_mtok numeric(12, 6);

ALTER TABLE model_usage
    ADD COLUMN cached_prompt_tokens bigint,
    ADD COLUMN prefix_tokens bigint,
    ADD COLUMN prefix_reuse_tokens bigint;
