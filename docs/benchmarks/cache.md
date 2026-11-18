# Prompt cache benchmark

Measurements of cache-aware context management ([F5](../features/f5-cache-aware-context.md), CACHE-1, CACHE-2, CACHE-7): how much of a long session the provider serves from its prompt cache, how much a perfect cache could serve, and what changes it. This page holds the phase 1 baseline on the context assembly before F5 changes it; later phases add their runs here.

## Setup

- Project: this repository at commit `10cf950`, executed by the real `pointer-executor`, so tools read the actual files.
- Model `deepseek-v4.1-flash:free` through TokenHarbor, Ask mode, routing off, one session per run, 18 November 2026. Embeddings `nomic-embed-text` through local Ollama.
- Script: [`infra/bench/cache/run.py`](../../infra/bench/cache/run.py). Usage comes from `GET /sessions/{id}/usage` after every turn: prompt tokens, `cachedPromptTokens` reported by the provider and the prefix reuse estimated by the backend.
- Session script, twelve turns in one session: nine lookup questions and two trace questions of the [routing benchmark](routing.md) in a fixed order (`q01, q02, q03, q05, q08, t02, q10, q12, t03, q18, q21`), then a probe. The first turn states a constraint (a release codename); the probe asks, without tools, for that codename and for the file named in the fifth answer.
- Variants:
  - **no memory**: the account has no memories, so the system prompt is the same in every turn;
  - **memory over budget**: 120 project memories, so the memory index exceeds its 6,000-character budget and is ordered by relevance to each turn's message (F4). This is the case the spec suspects of losing the cache.
- Cost uses illustrative prices: 0.50 USD per million input tokens and 2.00 per million output tokens, with cached input at 0.1, 0.25 and 0.5 of the input price. "Reported" bills the tokens the provider reported as cached at the cached price; "perfect cache" bills the estimated reusable share of the prompt at the cached price.

## Which providers report caching

Two-turn sessions on every model available for tests, before the baseline:

| Provider, model | Reports cached tokens | Cached share of prompt | Reusable share of prompt |
|---|---|---|---|
| TokenHarbor, `deepseek-v4.1-flash:free` | yes, `prompt_tokens_details.cached_tokens` | 0.78 | 0.79 |
| Experiential Labs, `deepseek-v4.1-flash` | yes, same field | 0.62 | 0.69 |
| Experiential Labs, `gpt-6-luna` | yes, same field | 0.73 | 0.80 |
| VyceAI, `glm-5.3-flash` | sends the field (0 on a failed call); model unavailable that day | – | – |

All three working models cache automatically, without a cache key or explicit markers, and the reported share follows the reusable share estimated by the backend within a few points. The first call of a session is sometimes served partly from the cache although it has no predecessor in that session: the system prompt and the tool definitions are identical across sessions, and the provider cache is not tied to a session.

## Baseline

| | No memory | Memory over budget |
|---|---|---|
| Turns completed | 12 / 12 | 12 / 12 |
| Model calls | 46 | 56 |
| Prompt tokens | 1,207,053 | 1,942,001 |
| Completion tokens | 10,621 | 13,140 |
| Cached prompt tokens, reported | 1,128,320 | 1,440,512 |
| Cached share of all prompt tokens | 0.935 | 0.742 |
| Reusable share (prefix reuse ratio) | 0.938 | 0.754 |
| Peak context, tokens | 50,812 | 66,170 |
| Compactions | 0 | 0 |
| Repeated tool calls | 0 of 33 | 0 of 45 |
| Coverage of expected files | 0.98 | 0.96 |
| Recall: codename / fifth file | yes / yes | yes / no |
| Duration, s | 156 | 220 |
| Cost without caching, USD | 0.625 | 0.997 |
| Cost at cached ratio 0.1, reported / perfect cache | 0.117 / 0.115 | 0.349 / 0.338 |
| Cost at cached ratio 0.25, reported / perfect cache | 0.202 / 0.200 | 0.457 / 0.448 |
| Cost at cached ratio 0.5, reported / perfect cache | 0.343 / 0.342 | 0.637 / 0.631 |

Cached and reusable share per turn:

| Turn | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 (probe) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| No memory, cached | 0.69 | 0.90 | 0.85 | 0.93 | 0.95 | 0.93 | 0.96 | 0.97 | 0.96 | 0.98 | 0.97 | 0.99 |
| No memory, reusable | 0.65 | 0.90 | 0.88 | 0.93 | 0.95 | 0.93 | 0.97 | 0.98 | 0.96 | 0.98 | 0.97 | 0.99 |
| Memory over budget, cached | 0.32 | 0.62 | 0.71 | 0.64 | 0.65 | 0.84 | 0.66 | 0.65 | 0.82 | 0.82 | 0.74 | 0.00 |
| Memory over budget, reusable | 0.49 | 0.66 | 0.74 | 0.66 | 0.67 | 0.85 | 0.67 | 0.67 | 0.83 | 0.83 | 0.75 | 0.02 |

- **The provider cache works as well as the prefix allows.** Without memories 93.5 % of all prompt tokens were served from the cache, and the reported cost is within 2 % of the perfect-cache cost at every price ratio. The only cold parts are each turn's new user message and the tool results of the last step.
- **A system prompt that changes per turn costs a third of the cache.** With the memory index over budget, the relevance order of the index changes with every user message, the system prompt differs in every turn, and the first call of each turn starts cold: the reusable share falls from 0.94 to 0.75 and stays high only within a turn (turns with more steps reuse more). The single-call probe turn reused 2 % of a 60,000-token request. Reported caching follows the same pattern, which confirms the first suspected source of lost cache in the spec.
- **Price impact.** The share of the prompt billed at full price is 6.5 % without and 25.8 % with the changing memory section. At a cached price of 0.1 of the input price that makes a prompt token cost 0.16 of the input price without and 0.33 with it, twice as much; at 0.5 the difference shrinks to 0.53 against 0.63. The two sessions also differ in length (the memory variant made ten more calls), so the per-token ratio is the comparable number, not the totals.
- **Quality.** Both sessions completed every turn without compaction. The memory variant missed the recall of the fifth file because the model miscounted the questions in its answer, not because the fact had left the context.

## Threats to validity

- One run per variant; model behaviour and provider cache state vary between runs.
- Prefix reuse is estimated at four characters per token and covers only calls with a predecessor in the same session and purpose, so it is a ratio, not an exact token count. The provider can serve the first call of a session from an earlier session's cache, which the estimate does not count.
- Calls of the memory variant reported cached tokens for 1,537,534 of 1,942,001 prompt tokens; shares above are computed over all prompt tokens, so unreported calls count as uncached.
- Prices are illustrative; the provider used is free.

## Reproducing

Keep an executor device online for the project, then:

```bash
POINTER_EMAIL=... POINTER_PASSWORD=... POINTER_DEVICE_ID=<executor device id> POINTER_PROJECT=pointer python infra/bench/cache/run.py --model deepseek-v4.1-flash:free --label baseline-nomem
```

Add `--memories 120` for the variant with the memory index over budget; it needs an embedding model in the account settings so that the index is ordered by relevance.
