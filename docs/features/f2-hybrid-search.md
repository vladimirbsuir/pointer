# F2. Hybrid code search and RAG benchmark

Status: In progress (phases 1 and 2 implemented) · Requirement ids: CTX-8 to CTX-10 · Depends on: semantic index (CTX-7, TOOL-14, [ADR 0013](../adr/0013-semantic-code-index.md)).

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

`rag/CodeTokens.expand(String)` produces the text for `to_tsvector('english', ...)` (English stemming, so `keys` matches `key` and `encryption` matches `encrypt`; other scripts pass through unchanged):

- split on non-alphanumerics; split `camelCase`, `PascalCase`, `snake_case`, `kebab-case`, letter/digit borders; tokens longer than 64 characters are dropped;
- keep both the whole identifier and its parts, lowercased: `SecretCipher` → `secretcipher secret cipher`;
- input per chunk: `path` + `symbol` weigh as `A`, `content` as `B` (`setweight`).

The same expansion is applied to the query; the tsquery is the OR of the expanded tokens (`to_tsquery('english', 't1 | t2 | ...')`), with single letters and English and Russian stop words removed in Java, so natural-language queries do not need every word to match.

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

`IndexService` keeps a constant `LEXICAL_VERSION` (1). When `index_states.lexical_version` is lower, the next search or indexing run recomputes `lexical` for all chunks of the project from the stored `content` in one transaction (no device calls, no embeddings) and sets the version. New and changed chunks get `lexical` on insert.

### Retrieval

`IndexService.rank` with `IndexStore.vectorCandidates` / `lexicalCandidates` and `RankFusion`:

1. Vector candidates: top 50 by cosine distance.
2. Lexical candidates: chunks where `lexical @@ query`, top 50 by `Σ idf(t) · ts_rank(lexical, t, 1)` over the query terms, with the BM25 inverse document frequency `idf(t) = ln(1 + (N − n_t + 0.5) / (n_t + 0.5))` counted per project in one aggregate query. Plain `ts_rank` has no IDF, so frequent words like `provider` or `key` outweighed the rare `encrypt` in the first live run. Each candidate also carries its cosine similarity.
3. Reciprocal rank fusion in Java: `score = Σ 1 / (60 + rank)` over the lists a chunk appears in; ties go to the higher cosine similarity.
4. Return the top `limit` with both component ranks. The `score` shown to the agent stays the cosine similarity.

Strategy `vector` takes only `limit` vector candidates and skips steps 2 and 3. Strategy `lexical` skips the query embedding and exists only for the query endpoint. The project strategy is changed with `PUT /projects/{id}/index/settings` `{searchStrategy: vector|hybrid}` and shown in the index dialog.

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

1. Query and document prefixes (small, independent, rerun the first measurement). Implemented: `EmbeddingPrefixes`, migration V18; the index status reports `prefixesChanged`, and starting an update with changed prefixes rebuilds the index.
2. Lexical column, `CodeTokens`, hybrid fusion, project setting. Implemented: migration V19, `CodeTokens`, `RankFusion`, `SearchStrategy`, the settings endpoint and the strategy switch in the index dialog. The query endpoint from phase 3 was built here too, to compare strategies on a real index.
3. Dataset, script; results recorded in `docs/` for the thesis.

## Early results

First live run on this repository (383 files, 1,297 chunks, `nomic-embed-text` with prefixes on CPU through Ollama, indexing took 629 s) with 10 hand-written questions, top 5 per strategy, after IDF weighting:

| Strategy | Expected file in top 5 | At rank 1 | Typical latency |
|---|---|---|---|
| vector | 6 / 10 | 4 / 10 | 55 to 300 ms (query embedding on CPU) |
| lexical | 7 / 10 | 5 / 10 | 15 to 35 ms |
| hybrid | 7 / 10 | 6 / 10 | 75 to 275 ms |

- Hybrid ranked first most often; lexical alone is the fastest and strongest on exact names (`SessionEventBus`, `RankFusion`, `kill_tree`).
- "encrypt provider API keys" finds `SecretCipher` only with IDF weighting (lexical rank 2, hybrid rank 4); vector search does not find it.
- A Russian question against English code fails in every strategy: vector search returns the Russian Telegram texts, lexical has no shared words. Cross-lingual retrieval is a topic for the phase 3 dataset.
- Ten questions are not a benchmark; the phase 3 dataset gives the numbers for the thesis.

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
