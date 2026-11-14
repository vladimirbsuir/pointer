ALTER TABLE models
    ADD COLUMN embedding_query_prefix    text,
    ADD COLUMN embedding_document_prefix text;

ALTER TABLE index_states
    ADD COLUMN embedding_query_prefix    text,
    ADD COLUMN embedding_document_prefix text;

UPDATE models
SET embedding_query_prefix = 'search_query: ', embedding_document_prefix = 'search_document: '
WHERE lower(model_key) ~ '(^|[^a-z0-9])nomic-embed($|[^a-z0-9])';

UPDATE models
SET embedding_query_prefix = 'query: ', embedding_document_prefix = 'passage: '
WHERE lower(model_key) ~ '(^|[^a-z0-9])e5($|[^a-z0-9])';

UPDATE models
SET embedding_query_prefix = 'Represent this sentence for searching relevant passages: '
WHERE lower(model_key) ~ '(^|[^a-z0-9])(bge-(small|base|large)|mxbai-embed|snowflake-arctic-embed)($|[^a-z0-9])';
