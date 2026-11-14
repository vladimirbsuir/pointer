ALTER TABLE code_chunks ADD COLUMN lexical tsvector;

CREATE INDEX code_chunks_lexical_idx ON code_chunks USING gin (lexical);

ALTER TABLE index_states
    ADD COLUMN lexical_version integer NOT NULL DEFAULT 0,
    ADD COLUMN search_strategy text NOT NULL DEFAULT 'hybrid' CHECK (search_strategy IN ('vector', 'hybrid'));
