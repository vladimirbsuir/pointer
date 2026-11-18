# Prompt cache benchmark

Measurements of cache-aware context management ([F5](../features/f5-cache-aware-context.md), CACHE-1 to CACHE-3, CACHE-7): how much of a long session the provider serves from its prompt cache, how much a perfect cache could serve, and what changes it. Phase 1 measured the context assembly as it was; phase 2 made the context append-only ([ADR 0024](../adr/0024-append-only-context.md)) and repeated the runs; phase 3 compared the context strategies. Phase 4 adds its runs here.

## Setup

- Project: this repository, executed by the real `pointer-executor`, so tools read the actual files. Phase 1 ran on commit `10cf950`, phase 2 on `f248c79`.
- Model `deepseek-v4.1-flash:free` through TokenHarbor, Ask mode, routing off, one session per run, 18 November 2026. Embeddings `nomic-embed-text` through local Ollama in both phases.
- Script: [`infra/bench/cache/run.py`](../../infra/bench/cache/run.py). Usage comes from `GET /sessions/{id}/usage` after every turn: prompt tokens, `cachedPromptTokens` reported by the provider and the prefix reuse estimated by the backend.
- Session script, twelve turns in one session: nine lookup questions and two trace questions of the [routing benchmark](routing.md) in a fixed order (`q01, q02, q03, q05, q08, t02, q10, q12, t03, q18, q21`), then a probe. The first turn states a constraint (a release codename); the probe asks, without tools, for that codename and for the file named in the fifth answer.
- Variants:
  - **no memory**: the account has no memories;
  - **memory over budget**: 120 project memories, so the memory index exceeds its 6,000-character budget and is ordered by relevance to each turn's message (F4).
- The numbers below are for the main agent loop (purpose `step`). The model can also delegate to the `explore` sub-agent, whose context is separate and not changed by F5; its calls are listed for completeness.
- Cost uses illustrative prices: 0.50 USD per million input tokens and 2.00 per million output tokens, with cached input at 0.1, 0.25 and 0.5 of the input price, applied to the tokens the provider reported as cached. "Cost per prompt token" is the billed price of an average prompt token relative to the full input price.

## Which providers report caching

Two-turn sessions on every model available for tests, before the baseline:

| Provider, model | Reports cached tokens | Cached share of prompt | Reusable share of prompt |
|---|---|---|---|
| TokenHarbor, `deepseek-v4.1-flash:free` | yes, `prompt_tokens_details.cached_tokens` | 0.78 | 0.79 |
| Experiential Labs, `deepseek-v4.1-flash` | yes, same field | 0.62 | 0.69 |
| Experiential Labs, `gpt-6-luna` | yes, same field | 0.73 | 0.80 |
| VyceAI, `glm-5.3-flash` | sends the field (0 on a failed call); model unavailable that day | – | – |

All three working models cache automatically, without a cache key or explicit markers, and the reported share follows the reusable share estimated by the backend within a few points. The first call of a session is sometimes served partly from the cache although it has no predecessor in that session: the system prompt and the tool definitions are identical across sessions, and the provider cache is not tied to a session. Experiential Labs also accepts `prompt_cache_key` (CACHE-6); its effect on hit rates was not measured.

## Results

Main agent loop, one session per column.

| | Phase 1, no memory | Phase 2, no memory | Phase 1, memory over budget | Phase 2, memory over budget |
|---|---|---|---|---|
| Turns completed | 12 / 12 | 12 / 12 | 12 / 12 | 12 / 12 |
| Model calls | 34 | 46 | 45 | 46 |
| Prompt tokens | 970,847 | 1,795,899 | 1,724,088 | 2,099,267 |
| Cached share, reported | 0.949 | 0.958 | 0.731 | **0.960** |
| Reusable share (prefix reuse ratio) | 0.951 | 0.961 | 0.744 | **0.962** |
| Cached share of the probe turn | 0.99 | 1.00 | 0.00 | **0.98** |
| Cost per prompt token, cached at 0.1 / 0.25 / 0.5 | 0.15 / 0.29 / 0.53 | 0.14 / 0.28 / 0.52 | 0.34 / 0.45 / 0.64 | **0.14 / 0.28 / 0.52** |
| Session cost, no cache, USD | 0.496 | 0.913 | 0.877 | 1.064 |
| Session cost, cached at 0.1, USD | 0.082 | 0.139 | 0.310 | **0.157** |
| Stored context snapshots, tokens | – | 1 / 160 | – | 12 / 18,075 |
| Peak context, tokens | 50,812 | 73,243 | 66,170 | 84,888 |
| Compactions | 0 | 0 | 0 | 0 |
| Tool calls of the main loop | 33 | 54 | 45 | 47 |
| `explore` calls, prompt tokens | 12, 236,206 | 0 | 11, 217,913 | 15, 271,348 |
| Coverage of expected files | 0.98 | 0.98 | 0.96 | 1.00 |
| Recall: codename / fifth file | yes / yes | yes / no | yes / no | yes / no |
| Duration, s | 156 | 158 | 220 | 230 |

Reusable share per turn, memory over budget:

| Turn | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 (probe) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Phase 1 | 0.49 | 0.66 | 0.74 | 0.66 | 0.67 | 0.85 | 0.67 | 0.67 | 0.83 | 0.83 | 0.75 | 0.02 |
| Phase 2 | 0.73 | 0.87 | 0.93 | 0.91 | 0.92 | 0.94 | 0.97 | 0.96 | 0.97 | 0.98 | 0.98 | 0.98 |

### Before phase 2

- **The provider cache works as well as the prefix allows.** Without memories 95 % of the main loop's prompt tokens were served from the cache, and the reported share is within a point of the reusable share. The only cold parts are each turn's new user message and the tool results of the last step.
- **A system prompt that changes per turn costs a quarter of the cache.** With the memory index over budget, its relevance order changes with every user message, the system prompt differs in every turn, and the first call of each turn starts cold: the cached share falls from 0.95 to 0.73 and stays high only within a turn (turns with more steps reuse more). The single-call probe turn got nothing from the cache for a 60,000-token request. This confirms the first suspected source of lost cache in the spec.
- **Price impact.** At a cached price of 0.1 of the input price an average prompt token cost 0.15 of the input price without and 0.34 with the changing memory section, more than twice as much; at 0.5 the difference shrinks to 0.53 against 0.64.

### After phase 2 (stable prefix)

- **The memory section no longer costs cache.** With the index still reordered in every turn, the cached share rose from 0.73 to 0.96, the same as without memories, and the probe turn was served 98 % from the cache instead of 0 %. The price of an average prompt token at a cached ratio of 0.1 fell from 0.34 to 0.14 of the input price, and the session cost at that ratio halved (0.310 to 0.157 USD) although the phase 2 session sent 22 % more prompt tokens.
- **The price is context growth.** Each changed snapshot stays in the conversation until the next compaction. With the index reordered every turn, all twelve turns stored a snapshot, 18,075 tokens in total, about a fifth of the 84,888-token peak context. It is billed cold once and cached afterwards, but it brings the compaction threshold closer; phase 3 and 4 decide whether that matters. Without memories only the first turn stored one (160 tokens).
- **No change without memories.** The no-memory session behaves as before (0.95 and 0.96 cached), as expected: the date and branch did not change within the run.
- **Session lengths differ.** The model's own choices vary between runs (34 to 46 calls, 33 to 54 main-loop tool calls), so totals are not comparable across columns; the per-token price and the cached share are.

### Tool order changes agent behaviour

The first phase 2 build also sorted every tool by name. In Ask mode that moved `explore` from the end to the top of the tool list. Two no-memory sessions on that build delegated almost every lookup to the sub-agent (70 and 83 `explore` calls against 12 before, 0.96 and 1.04 million sub-agent prompt tokens), and both ran two to three times longer (364 and 486 s). The order was not needed for determinism, since registry tools already come sorted from the database, so the final build keeps the previous order, and delegation returned to the previous level (0 and 15 calls). For the thesis this is a side result: tool position alone shifts how much a model delegates, so tool order belongs to the agent's behaviour and has to be kept stable across experiments.

## Context strategies

Phase 3 added `masking` and `hybrid` next to `summary` (CACHE-4). Same twelve-turn script and model, no memories, commit `11cce6e`, with the model's context window set to 40,000 tokens so that the 80 % compaction triggers within the session (at 32,000 tokens); masking keeps the newest 10 results. The recall probe now names the fifth question by its topic instead of its number. Numbers cover the agent's own calls, steps and compactions; the few `explore` calls are listed separately.

| | `summary` | `masking`, blocks of 10 | `hybrid`, blocks of 10 | `masking`, per step (block 1) |
|---|---|---|---|---|
| Turns completed | 12 / 12 | 12 / 12 | 12 / 12 | 12 / 12 |
| Model calls (of them compactions) | 54 (2) | 45 (0) | 39 (1) | 60 (0) |
| Prompt tokens | 1,033,577 | 724,099 | 785,280 | 1,016,554 |
| Prompt tokens per call | 19,140 | 16,091 | 20,135 | 16,943 |
| Peak context, tokens | 26,554 | 23,291 | 27,157 | 23,858 |
| Cached share, reported | 0.879 | 0.899 | 0.863 | **0.578** |
| Cost per prompt token, cached at 0.1 / 0.25 / 0.5 | 0.21 / 0.34 / 0.56 | 0.19 / 0.33 / 0.55 | 0.22 / 0.35 / 0.57 | 0.48 / 0.57 / 0.71 |
| Session cost, no cache, USD | 0.535 | 0.375 | 0.407 | 0.529 |
| Session cost, cached at 0.1, USD | 0.127 | **0.083** | 0.102 | 0.265 |
| Tool calls, repeated | 59, 0 | 47, 0 | 41, 0 | 77, 0 |
| `explore` calls | 0 | 4 | 9 | 0 |
| Coverage of expected files | 0.98 | 0.98 | 0.98 | 1.00 |
| Recall: codename / fifth answer | yes / yes | yes / yes | yes / yes | yes / yes |
| Duration, s | 164 | 170 | 160 | 180 |

- **Masking replaces compaction in a session of this length.** It never reached its 95 % safety limit, kept the peak context below the summary strategy's, which compacted twice, and sent 16 % fewer prompt tokens per call. It is also the cheapest variant: 0.083 USD at a cached ratio of 0.1, against 0.127 for `summary`. Compaction costs twice here: the summary call itself (about 20,000 prompt tokens each) and the cold rewrite of the context after it.
- **Hybrid sits in between.** With old results already masked, the context reached the 80 % limit once instead of twice, and the session cost 0.102 USD.
- **Block alignment is what makes masking cache-friendly.** Masking one more result on every step invalidates the cache from that result on, every call: the cached share fell from 0.90 to 0.58, an average prompt token cost 0.48 instead of 0.19 of the input price at a cached ratio of 0.1, and the session cost 3.2 times as much, more than `summary`. With blocks of ten the cache is lost once per ten results and the price of the extra unmasked results is small.
- **No sign of lost information.** No variant repeated a tool call it had already made, coverage stayed at 0.98 to 1.00, and every session recalled both the first-turn constraint and the file of the fifth answer, including after two compactions.
- **Session lengths still differ** (39 to 60 calls), so per-call and per-token figures are the comparable ones. The per-step `hybrid` run could not be completed: the provider's free allowance ran out after five turns, and its turns are left out.

## Threats to validity

- One run per variant and phase; model behaviour and provider cache state vary between runs.
- Prefix reuse is estimated at four characters per token and covers only calls with a predecessor in the same session and purpose, so it is a ratio, not an exact token count. The provider can serve the first call of a session from an earlier session's cache, which the estimate does not count.
- File recall by question number was unreliable: it failed in three of the six phase 1 and 2 sessions without any compaction, and every miss named the file of another turn, so the model miscounted the questions. Since phase 3 the probe names the question by its topic, and all four strategy sessions answered it correctly.
- Prices are illustrative; the provider used is free.

## Reproducing

Set up the backend, the benchmark account and the bench device as described in [the benchmark README](README.md#running-the-agent-benchmarks-locally), then:

```bash
eval "$(python infra/bench/account.py env)"
python infra/bench/cache/run.py --model deepseek-v4.1-flash:free --clear-memories --label nomem
```

Add `--strategy masking` or `--strategy hybrid` for the context strategies and `--context-window 40000` to make compaction trigger within the session; the block size is the backend property `pointer.context.mask-block` (for example `./gradlew bootRun --args='--pointer.context.mask-block=1'`). Add `--memories 120` for the variant with the memory index over budget; it needs an embedding model in the account settings so that the index is ordered by relevance, and the no-memory variant needs the project's memories deleted first (`--clear-memories`; `--embedding-model <key>` sets the embedding model).
