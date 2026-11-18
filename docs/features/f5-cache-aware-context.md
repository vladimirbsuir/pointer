# F5. Cache-aware context management

Status: In progress (phases 1 to 3 implemented) · Requirement ids: CACHE-1 to CACHE-7 · Depends on: context assembly and compaction ([ADR 0009](../adr/0009-context-compaction-and-plan-approval.md)), usage ledger and model prices ([F3](f3-model-routing.md), CTX-5, CTX-6), memory section ([F4](f4-agent-memory.md)).

## Goal

Providers bill the part of a request that repeats the previous one (the cached prefix) at a fraction of the input price, but the agent rebuilds its context on every call, so anything that rewrites the early part of it throws the cache away: compaction, a changed memory section, a trimmed tool result. F5 measures cache use per call, makes the conversation append-only between compactions, adds context strategies that trade old tool output for tokens and aligns them to the cache, and benchmarks the strategies on cost, task success and information loss. The result is a measured answer to "when does shrinking the context pay off once caching is priced in".

## Builds on

- `agent/ContextAssembler`: `build` (system prompt = base, mode, date, OS, shell, project, branch, global instructions, project rules, memory section; conversation from the items after the latest summary), `activeItems`, `StepGroup.flushInto` (renders tool results), `buildCompaction`, `compactable`.
- `agent/AgentRunner.runTurn`: `projectContext` and `memorySection` are built once per turn; automatic compaction at 80 % of the window; `Summarizer` runs on the fast model with fallback to the step model ([F3](f3-model-routing.md)).
- `provider/llm/StreamListener.Usage(promptTokens, completionTokens)`, `OpenAiCompatibleClient.requestBody` and `parse`, `ResolvedModel.cost`, `session/UsageLedger.record`, table `model_usage`.
- `memory/MemoryPrompt.section`: pinned memories in full, the rest as an index that is ordered by relevance to the user's message when it exceeds the budget.

Suspected sources of lost cache, to be confirmed by the phase 1 measurements:

- The memory section sits at the end of the system prompt, so every turn where it differs (relevance order for a new message, a memory saved by the agent) invalidates the whole history behind it.
- The date and the git branch are in the system prompt too.
- The compaction request has its own system prompt and usually goes to another model, so it shares no prefix with the agent's requests, and the context after it starts cold.
- Cost is computed as if every prompt token were billed at the full input price, so the ledger overstates cost on providers that cache.

## Requirements

| Id | Requirement |
|---|---|
| CACHE-1 | Usage records the cached part of the prompt when the provider reports it (`prompt_tokens_details.cached_tokens` or `prompt_cache_hit_tokens`). A model can have a cached input price; cost uses it, and without it behaves as today. |
| CACHE-2 | Every model call records how much of its request repeats the previous call of the same session and purpose, whether or not the provider reports caching. The usage view shows cache hit ratio and prefix reuse ratio. |
| CACHE-3 | Between compactions the model's view of a session is append-only: volatile context (date, git branch, retrieved memory) is stored per turn and rendered with the turn's user message only when it changed, and tool definitions have a deterministic order. |
| CACHE-4 | A context strategy, a session setting with a user default, selects `summary` (today), `masking` (old tool results replaced by a placeholder) or `hybrid` (masking, then summary at the usual limit). Masking moves in blocks so that the prefix stays stable between moves. |
| CACHE-5 | Compaction can reuse the agent's own prefix when it runs on the session model, and an extra compaction at a turn boundary happens when the expected saving exceeds its cost. The compaction at 80 % of the window stays as the hard limit. |
| CACHE-6 | A provider can be marked to receive a prompt cache key (the session id) with every request. |
| CACHE-7 | A benchmark runs long sessions with each strategy and reports cost, tokens, cache use, compactions, task success and recall of facts stated early in the session. |

## Design

### Data model

Phase 1 adds the measurement columns in `V28__cache_usage.sql` (`models.cached_input_price_per_mtok`, `model_usage.cached_prompt_tokens`, `prefix_tokens`, `prefix_reuse_tokens`), phase 2 adds `turns.context_snapshot` and `providers.sends_cache_key` in `V29__stable_prefix.sql`; the strategy columns come with phase 3.

```sql
ALTER TABLE models
    ADD COLUMN cached_input_price_per_mtok numeric(12, 4);

ALTER TABLE providers
    ADD COLUMN sends_cache_key boolean NOT NULL DEFAULT false;

ALTER TABLE model_usage
    ADD COLUMN cached_prompt_tokens bigint,
    ADD COLUMN prefix_tokens bigint,
    ADD COLUMN prefix_reuse_tokens bigint;

ALTER TABLE user_settings
    ADD COLUMN context_strategy_default text NOT NULL DEFAULT 'summary'
        CHECK (context_strategy_default IN ('summary', 'masking', 'hybrid'));

ALTER TABLE sessions
    ADD COLUMN context_strategy text NOT NULL DEFAULT 'summary'
        CHECK (context_strategy IN ('summary', 'masking', 'hybrid'));

ALTER TABLE turns
    ADD COLUMN context_snapshot text;
```

`cached_prompt_tokens` is null when the provider does not report it, which differs from zero. `prefix_reuse_tokens` is null for the first call of a session.

### Measuring (CACHE-1, CACHE-2)

- `StreamListener.Usage` gains `cachedPromptTokens` (nullable). `OpenAiCompatibleClient.parse` reads `usage.prompt_tokens_details.cached_tokens`, then `usage.prompt_cache_hit_tokens`; absent means null.
- `ResolvedModel.cost(prompt, cached, completion)` bills `(prompt - cached)` at the input price, `cached` at the cached input price (the input price when it is not set) and `completion` at the output price. `UsageLedger.record`, session totals and `BudgetGuard` use it.
- `routing/PrefixFingerprint`: a hash of the tool definitions, of the system message and of every message in order, kept for the latest call per (session, purpose) in a bounded in-memory map (2,000 entries, least recently used evicted). The common run of leading hashes, converted to tokens with `provider/llm/TokenEstimate` (the estimate `ContextAssembler.estimateTokens` uses), is `prefix_reuse_tokens`; the estimate for the whole request is `prefix_tokens`. A restart loses the map, so the first call after it records null. A call on another model than the previous one (failover) records 0, because that model's cache cannot serve it.
- `UsageLedger.record` takes the request and measures every call, also those without reported usage, so that the next call compares against the right predecessor; only calls with usage get a row.
- Prefix reuse is the share of the request that a perfect cache could serve. The provider's real hit ratio is lower because caches have a minimum prefix length, a block size and a time to live, so both numbers are shown and compared in the benchmark.
- `GET /sessions/{id}/usage` rows gain `cachedPromptTokens` and `cacheReportedPromptTokens` (prompt tokens of the calls that reported caching, the denominator of the hit ratio), `prefixTokens` and `prefixReuseTokens` (summed over calls that had a predecessor, the denominator and numerator of the reuse ratio).

### Stable prefix (CACHE-3, CACHE-6)

`ContextAssembler.build` changes:

- The system prompt keeps what changes only when the user edits it: base instructions, mode instructions, OS and shell, project name, global instructions, project rules.
- Date, git branch and the memory section move to the turn context snapshot. `AgentRunner` builds it at the start of the turn as today and stores it in `turns.context_snapshot` only when it differs from the snapshot of the previous turn of the session; otherwise the column stays null.
- The turn's first user message is rendered with the snapshot in front, as a block that says it supersedes earlier context blocks. A turn without a snapshot renders as before. Because the snapshot is stored, the same items always render to the same messages.
- After a compaction, the latest snapshot of the turns the summary replaced (turns without a user message in the active items) is rendered in front of the summary message, unless the first active turn has its own snapshot. The first draft carried a snapshot only while no active turn had one; the property test showed that the summary message then changes as soon as a later turn gains a snapshot, which breaks the prefix. Both inputs of the final rule are fixed between compactions.
- Tool order was already deterministic: registry tools come sorted by name from the database and the server-side tools follow in a fixed order. Sorting all of them by name was tried and reverted, because it moved `explore` to the top of the Ask-mode list and changed how often the model delegated ([benchmark](../benchmarks/cache.md#after-phase-2-stable-prefix)). What was not deterministic is the schema serialization: `ChatRequest.ToolDefinition` now copies its JSON schema with `StableMaps`, which keeps maps built in order and sorts unordered ones (`Map.of`, whose iteration order changes with every JVM start) by key. `ProposeTool.definition` already lists interpreters in a fixed order.
- The title request uses the raw text of the first user message, without the context block.
- A change of mode between turns still changes the system prompt and the tool list and is accepted as a cache reset.
- With `providers.sends_cache_key`, `OpenAiCompatibleClient.requestBody` adds `prompt_cache_key` with the session id. It is off by default because not every OpenAI-compatible server accepts unknown fields.

Invariant, checked by a property test (`ContextAssemblerTests`, 300 generated sessions with tool steps, queued follow-ups, snapshots and compactions): between two compactions, the messages built at every model call are a strict prefix of the messages built at the next one. The property holds at call boundaries, where every tool call of the last step has its result; between them a step's assistant message still grows. The decision is recorded in [ADR 0024](../adr/0024-append-only-context.md).

### Strategies (CACHE-4)

- `summary`: today's behaviour.
- `masking`: masking only. The summary is not written at the usual 80 %; a safety compaction at 95 % of the window keeps a session from overflowing (`ContextPolicy.MASKING_SAFETY_RATIO`). Without it the strategy would fail long sessions, and with it at 80 % it would be the same as `hybrid`.
- Masking in detail: in `StepGroup.flushInto`, tool results older than the mask boundary are replaced by `[Result of <tool>(<short arguments>) omitted: <n> lines. Run the tool again if you need it.]`. The assistant's text and tool calls stay. Results under 200 characters are never masked, because the placeholder would not save anything. Original items stay in the database and in the client, as with compaction.
- `hybrid`: masking, and summarization at 80 % of the window as today; the summary input is smaller because old results are already masked.
- Block alignment: with `keep` newest results unmasked (default 10) and blocks of `block` results (default 10), the boundary is `floor((results - keep) / block) * block` counted over the active conversation. It depends only on the number of results, so it is the same on every call, and it moves once per `block` results. Between moves the context is append-only; a move invalidates the cache from the first newly masked result, which costs one cold write of the tail. The alternative of masking one result per step invalidates the tail on every call; the benchmark compares both (`block = 1` is the per-step variant).
- `keep` and `block` are application properties (`pointer.context.keep-results`, `pointer.context.mask-block`), not user settings.
- Not masked: results of the explore sub-agent's nested items (already outside the main context), plans (`propose_plan`), and the results of the turn in progress when they are within the newest `keep`. The report of an `explore` call is an ordinary tool result and can be masked.
- Implementation: `agent/MaskPolicy` (keep, block, boundary arithmetic), `agent/ContextPolicy` (strategy to mask and compaction ratio), `ContextAssembler.build` and `buildCompaction` take the policy; the property test runs under several policies and allows a difference only at the first newly masked result when the boundary moved.

### Compaction that reuses the cache (CACHE-5)

- Cache-safe request: when the compaction model equals the step model, the request is the step request unchanged (same system prompt, same tools, same messages) with the compaction instruction appended as the last user message; only that instruction and the summary are new tokens. The instruction forbids tool calls; a tool call in the answer fails the attempt, which is retried the old way.
- `routing/CompactionPlanner.choose`: picks between the fast model with the old request and the step model with the cache-safe request by comparing estimated costs from the ledger's prices and the cached input price. Without a cached price it keeps today's choice.
- Soft trigger at a turn boundary, evaluated when the context is at least 30 % of the window and a cached price is known. With `C` the current context, `S` the average size of this session's earlier summaries (2,000 tokens when none), `N` the average model calls per turn of the session (8 when none), `p` the input price, `q` the cached input price and `k` the cost of the compaction call itself: compact when `N · (C - S) · q > k + S · (p - q)`. The left side is what the shorter context saves over one more turn; the right side is the compaction call plus the one cold write of the new prefix. After an idle gap longer than the provider's cache lifetime the cache is already cold, so `q` is replaced by `p` in the cold-write term (the gap is the time since the previous call; the lifetime is a property, default 5 minutes).
- Compaction of the turn in progress remains impossible (ADR 0009), so the soft trigger runs only before the first call of a turn.

### API and protocol

- Settings DTO: `contextStrategyDefault`. Session create/update DTO and response: `contextStrategy`. Model DTO: `cachedInputPricePerMtok`. Provider DTO: `sendsCacheKey`.
- Usage rows: `cachedPromptTokens`, `prefixTokens`, `prefixReuseTokens`.
- Update [03-protocol.md](../03-protocol.md) and [04-data-model.md](../04-data-model.md) in the same commits.

### Desktop

- Settings: default context strategy; model options: cached input price; provider options: "Send prompt cache key".
- Session header popover: strategy selector next to the routing switch.
- Usage dialog: cached tokens, cache hit ratio and prefix reuse ratio per model and purpose; cost already reflects the cached price.
- The conversation view is unchanged by masking: only the model's view shrinks.

### Telegram

- The cost footer uses the cache-aware cost.

## Phases

1. Measurement (CACHE-1, CACHE-2): migration, usage parsing, cached price in cost, `PrefixFingerprint`, usage view, desktop columns, and the baseline benchmark run on the current context assembly. Shippable alone: the numbers answer which test providers report cached tokens and how much of each session is reusable today. **Implemented**; baseline in [benchmarks/cache.md](../benchmarks/cache.md), script [`infra/bench/cache/run.py`](../../infra/bench/cache/run.py).
2. Stable prefix (CACHE-3, CACHE-6): snapshot column and rendering, tool ordering, cache key, the append-only property test; repeat the baseline run. **Implemented**; ADR 0024, results in [benchmarks/cache.md](../benchmarks/cache.md#after-phase-2-stable-prefix).
3. Strategies (CACHE-4): masking and hybrid with block alignment, setting, desktop selector. **Implemented** (migration `V30__context_strategies.sql`); results in [benchmarks/cache.md](../benchmarks/cache.md#context-strategies).
4. Cache-reusing compaction and the soft trigger (CACHE-5), the full benchmark (CACHE-7) and an ADR that supersedes the compaction part of [ADR 0009](../adr/0009-context-compaction-and-plan-approval.md). Open items carried over from phase 3: the per-step `hybrid` run (block 1) is missing because the provider's free allowance ran out, and the benchmark model in the test account still has its context window set to 40,000 tokens. The benchmark needs an executor device online for the project (the desktop app or a headless device built on `pointer-executor`), see [Reproducing](../benchmarks/cache.md#reproducing).

## Tests

- Usage parsing for both field styles and for absence; cost with and without a cached price.
- `PrefixFingerprint`: identical requests, appended messages, a changed system prompt, a changed tool.
- Append-only property test over generated conversations, including a compaction in the middle and a turn that gains a snapshot.
- Snapshot delta: stored only on change, rendered with the right user message, carried over a compaction.
- Masking: boundary arithmetic, short results kept, nested explore items excluded, same output on repeated builds, `block = 1` equals per-step masking.
- `CompactionPlanner` and the soft trigger on a table of price and size cases, including an unknown cached price and an idle gap.
- `AgentRunner` regression: strategy `summary` with no snapshot changes produces the same messages as before apart from the moved blocks.

## Thesis metrics

Task set: scripted sessions of twelve turns over this repository, built from the lookup and trace tasks of the [routing benchmark](../benchmarks/routing.md) in a fixed order, each with two recall probes: a constraint stated in the first turn and a file path established in the fifth are asked about in the last turn. Cost is computed from token counts with price ratios of cached to uncached input of 0.1, 0.25 and 0.5, as in the routing benchmark, plus the real prices of any provider that reports cached tokens.

- Prefix stability: cache hit ratio, prefix reuse ratio and cost per session before and after phase 2, with the memory index over its budget so that relevance ordering changes between turns.
- Strategy matrix: `summary`, `masking`, `hybrid`, each with per-step and block-aligned masking: total and billed-equivalent prompt tokens, cost, peak context, number of compactions, coverage of expected files, recall-probe accuracy, share of tool calls that repeat an earlier call (a sign that masking removed something the model needed), duration.
- Break-even: the cached price ratio at which block-aligned masking beats per-step masking and at which cache-reusing compaction beats compaction on the fast model, derived from the formulas above and compared with the measured crossover.
- Soft trigger: cost with and without it on the same sessions.

## Open questions

- Phase 2 measured that a memory index reordered by relevance stores a new snapshot in every turn, about 1,500 tokens each (18,000 over twelve turns). If the growth turns out to matter with masking and compaction, the options are a snapshot that carries only the changed lines, or an index order that stays fixed within a session unless the set of memories changes.

- Answered in phase 1: Experiential Labs (`deepseek-v4.1-flash`, `gpt-6-luna`) and TokenHarbor (`deepseek-v4.1-flash:free`) report `prompt_tokens_details.cached_tokens`; VyceAI sends the field as well.
- Provider caches differ in minimum prefix length, block size and lifetime; the property for the lifetime is a single default, and measuring it per provider is not planned.
- Mode changes reset the cache because the tool list and mode text sit in the prefix; keeping all tools in every mode would stop this but changes agent behaviour in Ask and Plan modes. The benchmark counts mode switches, which decides whether it matters.
- Native cache breakpoints for providers that need explicit markers (for example Anthropic's `cache_control`) require the specialized adapter of PROV-8 and are not planned.
