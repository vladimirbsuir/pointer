# RAG benchmark

Measurements of the semantic code index ([F2](../features/f2-hybrid-search.md), CTX-7 to CTX-10) on this repository. The numbers are for the thesis; the scripts make them reproducible.

## Setup

- Index: this repository at commit `f2a9159`, 385 files, 1,305 chunks, chunked by the real `pointer-executor` (tree-sitter).
- Embeddings: `nomic-embed-text` through local Ollama on CPU, query prefix `search_query: `, document prefix `search_document: ` unless stated otherwise. A full index build takes about 10.5 minutes, dominated by the model.
- Backend: local `bootRun` with PostgreSQL and pgvector from `infra/docker-compose.yml`.
- Latency is `tookMs` of `POST /projects/{id}/index/query`: query embedding plus ranking, without the incremental freshness pass.

## Dataset

[`infra/bench/rag/questions.json`](../../infra/bench/rag/questions.json): 45 questions written against this repository.

| Group | Questions | What they test |
|---|---|---|
| `kind=behaviour` | 31 | "How does X happen", wording different from the code |
| `kind=concept` | 7 | Domain concepts without identifiers |
| `kind=identifier` | 7 | Exact class, method or function names |
| `lang=en` | 36 | English questions |
| `lang=ru` | 9 | Russian questions against English code, mostly translations of English ones |

Each question lists one or more acceptable files. A hit is a returned chunk from one of them; `hit@k` is the share of questions with a hit in the top `k`, `MRR` the mean reciprocal rank of the first hit.

## Retrieval

`infra/bench/rag/run.py`, all 45 questions, top 10:

| Group | Strategy | hit@1 | hit@5 | hit@10 | MRR | p50 ms | p95 ms |
|---|---|---|---|---|---|---|---|
| all (45) | vector | 0.36 | 0.69 | 0.73 | 0.48 | 192 | 271 |
| all (45) | lexical | 0.42 | 0.69 | 0.80 | 0.53 | 19 | 26 |
| all (45) | **hybrid** | **0.51** | **0.73** | **0.82** | **0.59** | 221 | 287 |
| en (36) | vector | 0.44 | 0.83 | 0.89 | 0.59 | 188 | 271 |
| en (36) | lexical | 0.53 | 0.83 | 0.94 | 0.65 | 20 | 26 |
| en (36) | **hybrid** | **0.64** | **0.89** | **0.97** | **0.73** | 217 | 265 |
| ru (9) | vector | 0.00 | 0.11 | 0.11 | 0.06 | 215 | 265 |
| ru (9) | lexical | 0.00 | 0.11 | 0.22 | 0.05 | 10 | 20 |
| ru (9) | hybrid | 0.00 | 0.11 | 0.22 | 0.04 | 233 | 304 |
| behaviour (31) | vector | 0.32 | 0.68 | 0.71 | 0.45 | 190 | 265 |
| behaviour (31) | lexical | 0.32 | 0.65 | 0.77 | 0.45 | 20 | 26 |
| behaviour (31) | **hybrid** | **0.45** | **0.74** | **0.84** | **0.55** | 223 | 265 |
| concept (7) | vector | 0.14 | 0.43 | 0.57 | 0.30 | 197 | 271 |
| concept (7) | lexical | 0.43 | 0.57 | 0.71 | 0.50 | 19 | 26 |
| concept (7) | hybrid | 0.43 | 0.43 | 0.57 | 0.45 | 221 | 287 |
| identifier (7) | vector | 0.71 | 1.00 | 1.00 | 0.83 | 193 | 658 |
| identifier (7) | lexical | 0.86 | 1.00 | 1.00 | 0.93 | 12 | 18 |
| identifier (7) | hybrid | 0.86 | 1.00 | 1.00 | 0.93 | 216 | 244 |

Effect of the embedding prefixes (CTX-9): the same run after rebuilding the index without prefixes (1,311 chunks, the benchmark files themselves had been added):

| Group | Strategy | hit@1 | hit@5 | hit@10 | MRR |
|---|---|---|---|---|---|
| all (45) | vector, no prefixes | 0.29 | 0.69 | 0.73 | 0.45 |
| all (45) | vector, prefixes | 0.36 | 0.69 | 0.73 | 0.48 |
| all (45) | hybrid, no prefixes | 0.40 | 0.73 | 0.82 | 0.53 |
| all (45) | hybrid, prefixes | 0.51 | 0.73 | 0.82 | 0.59 |
| en (36) | hybrid, no prefixes | 0.50 | 0.89 | 0.97 | 0.65 |
| en (36) | hybrid, prefixes | 0.64 | 0.89 | 0.97 | 0.73 |

The prefixes do not change which files reach the top 10, but they move the right one to the first place more often: hit@1 of hybrid search grows from 0.40 to 0.51.

Findings:

- Hybrid search is the best strategy overall: on English questions it puts the right file first in 64 % of cases against 44 % for vector search, and finds it in the top 10 in 97 % against 89 %.
- Full-text search alone is already stronger than vector search on this codebase and ten times faster; vector search adds value on behaviour questions whose wording does not occur in the code.
- Concept questions are the weak spot of the fusion: when only one ranker finds the file at a low rank, documents found by both rankers push it down (for example "encrypt provider API keys": lexical rank 3, hybrid outside the top 10).
- Russian questions against English code fail with every strategy. `nomic-embed-text` is an English model and full-text search has no shared words. Candidates: a multilingual embedding model (for example `bge-m3`) or translating the query with the chat model before searching.
- Latency of vector and hybrid search is dominated by embedding the query on CPU (about 190 ms); ranking itself takes 10 to 30 ms.

## Agent-level check

`infra/bench/rag/agent_eval.py`, 10 English questions (q01, q02, q03, q05, q08, q10, q12, q18, q21, q26), Ask mode, model `glm-5.3-flash` through VyceAI, one run each. "With index" uses the indexed project; "without index" uses a second project linked to the same folder without an index, so `semantic_search` is not offered at all.

| Variant | Correct answers | Tool calls per question | `semantic_search` calls | Input tokens | Output tokens | Seconds |
|---|---|---|---|---|---|---|
| with index | 10 / 10 | 2.6 | 0.9 | 13,124 | 368 | 10.6 |
| without index | 10 / 10 | 3.5 | 0 | 14,393 | 380 | 13.5 |

- Answers were checked by hand: every answer names the right file and main function.
- With the index the agent needs 26 % fewer tool calls, 9 % fewer input tokens and 21 % less time; the largest difference is on questions without obvious keywords (q08: 3 against 6 calls, q18: 3 against 5).
- On a repository of this size `grep` is a strong baseline for a capable model, so correctness does not differ; the index pays off in steps and tokens. Ten questions and one run per variant are a small sample.

## Reproducing

1. Start PostgreSQL and the backend, configure an embedding model and build the project index from the desktop app (Project menu → Semantic index).
2. Retrieval:

```bash
POINTER_EMAIL=... POINTER_PASSWORD=... POINTER_PROJECT=pointer python infra/bench/rag/run.py --label "my run"
```

3. Agent-level check: link the same folder as a second project without an index, keep the desktop app (the executor) online and run:

```bash
POINTER_EMAIL=... POINTER_PASSWORD=... POINTER_DEVICE_ID=<desktop device id> python infra/bench/rag/agent_eval.py --indexed-project pointer --plain-project pointer-plain --model glm-5.3-flash
```

Results are written to `infra/bench/rag/results/` (ignored by git): a JSON file with every question and a Markdown table.
