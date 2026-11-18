ALTER TABLE turns
    ADD COLUMN context_snapshot text;

ALTER TABLE providers
    ADD COLUMN sends_cache_key boolean NOT NULL DEFAULT false;
