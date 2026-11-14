# F2. Hybrid code search and RAG benchmark

Status: Approved · Requirement ids: CTX-8 to CTX-10 · Depends on: semantic index (CTX-7, TOOL-14, [ADR 0013](../adr/0013-semantic-code-index.md)).

## Goal

Combine full-text search over code identifiers with vector search, fix embedding prefixes for models that need them, and produce a reproducible benchmark that compares vector-only, lexical-only and hybrid retrieval on this repository. The benchmark table is a measured result for the thesis.

## Builds on

- `rag/IndexService` (incremental pass), `rag/IndexStore` (exact cosine scan, `order by embedding <=> :query`), `rag/Embedder` (batched `/embeddings`), `rag/SemanticSearchOperation` (`semantic_search` as a `ServerOperation`).
- Tables `code_chunks`, `indexed_files`, `index_states` (V14).
- Known miss from the first run: "encrypt provider API keys" did not find `SecretCipher` ([roadmap M7](../05-roadmap.md)).

## Requirements

| Id | Requirement |
|---|---|
| CTX-8 | `semantic_search` ranks by fusing vector similarity with full-text relevance over identifier-aware tokens; the strategy is a project setting (`hybrid` by default, `vector` available). |
| CTX-9 | Embedding models can have query and document prefixes; known model families get defaults; changing the document prefix rebuilds the index. |
| CTX-10 | A benchmark script runs a fixed question set against a project index with each strategy and reports hit@1, recall@5, recall@10, MRR and latency. |

## Design

### Lexical tokens

`rag/CodeTokens.expand(String)` produces the text for `to_tsvector('simple', ...)`:

- split on non-alphanumerics; split `camelCase`, `PascalCase`, `snake_case`, `kebab-case`, letter/digit borders;
- keep both the whole identifier and its parts, lowercased: `SecretCipher` → `secretcipher secret cipher`;
- input per chunk: `path` + `symbol` + `content`; path segments weigh as `A`, symbol as `A`, content as `B` (`setweight`).

The same expansion is applied to the query; the tsquery is the OR of the expanded tokens (`to_tsquery('simple', 't1 | t2 | ...')`), stop words of English and Russian removed in Java, so natural-language queries do not need every word to match.

### Schema (`Vn__hybrid_search.sql`)

```sql
ALTER TABLE code_chunks ADD COLUMN lexical tsvector;
CREATE INDEX code_chunks_lexical_idx ON code_chunks USING gin (lexical);

ALTER TABLE index_states
    ADD COLUMN lexical_version integer NOT NULL DEFAULT 0,
    ADD COLUMN search_strategy text NOT NULL DEFAULT 'hybrid' CHECK (search_strategy IN ('vector', 'hybrid'));

ALTER TABLE models
    ADD COLUMN embedding_query_prefix text,
    ADD COLUMN embedding_document_prefix text;
```

`IndexService` keeps a constant `LEXICAL_VERSION`. When `index_states.lexical_version` is lower, it recomputes `lexical` for all chunks of the project from the stored `content` in batches (no device calls, no embeddings) and sets the version. New and changed chunks get `lexical` on insert.

### Retrieval

`IndexStore.search(projectId, queryVector, queryText, pathPrefix, limit, strategy)`:

1. Vector candidates: top 50 by cosine distance (existing query).
2. Lexical candidates: top 50 by `ts_rank_cd(lexical, query, 32)` where `lexical @@ query`.
3. Reciprocal rank fusion in Java: `score = Σ 1 / (60 + rank)` over the lists a chunk appears in.
4. Return the top `limit` with both component ranks kept for debugging.

Strategy `vector` skips steps 2 and 3. A strategy `lexical` exists only for the benchmark endpoint.

### Prefixes

- `Embedder.embedQuery` prepends `embedding_query_prefix`, `Embedder.embedDocuments` prepends `embedding_document_prefix`.
- Defaults when a model is discovered or created, by `model_key` substring: `nomic-embed` → `search_query: ` / `search_document: `; `e5` → `query: ` / `passage: `; `bge` → query prefix `Represent this sentence for searching relevant passages: `, no document prefix. Editable in the model dialog.
- `index_states` stores the document prefix used; a mismatch marks the index for a full rebuild, as a model change does now.

### Benchmark

- Endpoint `POST /api/v1/projects/{projectId}/index/query` `{query, limit, strategy: vector|lexical|hybrid}` → ranked chunks `{path, symbol, startLine, endLine, score, vectorRank, lexicalRank}` and `tookMs`. Authenticated, owner only; runs the same incremental pass as the tool.
- Dataset `infra/bench/rag/questions.json`: 40 to 50 entries `{id, query, lang: en|ru, kind: concept|identifier|behaviour, expected: [{path, symbol?}]}` written against a pinned commit of this repository. A hit is a returned chunk with a matching path and, when given, a symbol containing the expected one.
- Script `infra/bench/rag/run.py` (standard library only): signs in with credentials from environment variables, runs every query with each strategy, writes `results-<date>.json` and a Markdown table: hit@1, recall@5, recall@10, MRR, p50 and p95 latency, per strategy and per `kind`/`lang`.
- Agent-level check: 10 questions in Ask mode with `semantic_search` allowed and denied (tool policy), compared by tool calls, prompt tokens and answer correctness judged by hand.

## Phases

1. Query and document prefixes (small, independent, rerun the first measurement).
2. Lexical column, `CodeTokens`, hybrid fusion, project setting.
3. Benchmark endpoint, dataset, script; results recorded in `docs/` for the thesis.

## Tests

- `CodeTokens`: camel, Pascal, snake, kebab, digits, Cyrillic text, stop words.
- `IndexStore.search` with Testcontainers pgvector: fusion order on hand-made vectors and texts, path filter, strategy switch, lexical backfill by version.
- `Embedder`: prefixes applied to queries and documents, defaults by model key.

## Thesis metrics

- The benchmark table: vector vs lexical vs hybrid, with and without prefixes, on `nomic-embed-text` and one cloud embedding model.
- Russian queries against English code: cross-lingual retrieval quality per strategy.
- Index build time and search latency at this repository's size (about 300 files, 900+ chunks).

## Not in scope

- HNSW indexes ([ADR 0013](../adr/0013-semantic-code-index.md) keeps the exact scan).
- Re-ranking with an LLM or cross-encoder, a repository map from the syntax tree: candidates for later specs.
