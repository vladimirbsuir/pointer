# 0018. Hybrid code search

Status: Accepted. Supersedes the search part of [ADR 0013](0013-semantic-code-index.md).

## Context

[ADR 0013](0013-semantic-code-index.md) ranks `semantic_search` results by an exact cosine scan over chunk embeddings. The first live run on this repository missed exact class names (`SecretCipher` for "encrypt provider API keys"), and `nomic-embed-text` expects query and document prefixes that were not sent. Feature spec: [F2](../features/f2-hybrid-search.md).

## Decision

- **Prefixes per model.** Models store an optional query and document prefix, filled for known embedding families. The index records the prefixes it was built with and keeps using them until a rebuild; changed prefixes make the next update rebuild the index.
- **Full-text column next to the vector.** Every chunk also stores a `tsvector` (`english` configuration) of identifier-aware tokens: whole identifiers and their camel, snake and kebab parts, path and symbol with weight A, content with weight B. A tokenizer version in `index_states` re-tokenizes old indexes from the stored content on the next search.
- **Rank fusion.** The project strategy is `hybrid` by default: top 50 by cosine distance and top 50 by full-text rank weighted with BM25 inverse document frequency, fused by reciprocal rank (k = 60). `vector` stays available per project. The score returned to the agent remains the cosine similarity.
- **Evaluation endpoint.** `POST /projects/{id}/index/query` runs the same retrieval with an explicit strategy, including `lexical`, for the benchmark in [benchmarks/rag.md](../benchmarks/rag.md).

## Consequences

- On 45 questions hybrid search reaches hit@1 0.51 and MRR 0.59 against 0.36 and 0.48 for vector search; full-text search alone costs 10 to 30 ms.
- Each search runs two queries and an inverse document frequency count over the project; this is cheap at the targeted size of tens of thousands of chunks.
- Questions in another language than the code still fail; a multilingual embedding model would be needed.

## Rejected alternatives

- **Plain `ts_rank` without IDF.** Common words like `provider` outweighed rare ones like `encrypt`.
- **Re-ranking with an LLM or a cross-encoder.** Better quality is possible, but it adds latency and cost to every search.
- **A separate search engine.** PostgreSQL already holds the chunks; a second store would need synchronisation.
