# 0025. Cache-aware compaction

Status: Accepted. Supersedes the compaction part of [ADR 0009](0009-context-compaction-and-plan-approval.md); plan approval in ADR 0009 stays as it is. Builds on [ADR 0020](0020-model-roles-and-usage-ledger.md) (compaction on the fast model) and [ADR 0024](0024-append-only-context.md) (append-only context).

## Context

ADR 0009 compacted a session only when its context reached 80 % of the window, with a request of its own: a summarizer system prompt, no tools, and the conversation re-rendered behind it. ADR 0020 moved that request to the fast model. Since ADR 0024 the agent's own requests are append-only between compactions, so a provider's prompt cache serves almost all of every step from the cached prefix. A compaction request with another system prompt shares no prefix with them, so it pays the full input price for the whole conversation, on whichever model it runs.

Caching also changes when compacting pays off. A long context costs little per call while it is cached, but every call still pays for it at the cached price, and a compaction replaces it with a short summary at the cost of one summarization call and one cold write of the summary. With a cached price known, the cost of both paths can be estimated before each turn. Feature spec: [F5](../features/f5-cache-aware-context.md) (CACHE-5).

## Decision

- **Cache-safe request.** When compaction runs on the step model, its request is the step request for the turns it covers: the same system prompt, the same tool list in the same order, the same rendered messages and mask boundary, with one user message appended that asks for the summary and forbids tool calls. Only that instruction and the summary are new tokens. A property test checks that the request without the instruction is a prefix of the step request built at the same moment.
- **Tool calls fail the attempt.** The tools stay in the request only to keep the prefix; an answer that calls a tool is discarded (its partial item is marked failed and hidden in the client) and the compaction is retried with the old summarizer request, which has no tools.
- **Model choice by cost** (`CompactionPlanner.choose`). The old choice of ADR 0020 stays the default: the fast model when its window fits the conversation, otherwise the step model. When the old choice is the step model, the request is cache-safe. When it is the fast model and the step model has a cached input price, both options are priced: the fast model with the old request at its input price, and the step model with the cache-safe request, reading the conversation at the cached price when the cache is warm. The cheaper one wins; without a cached price, or when the fast model has no prices, the fast model keeps the old request. A failed fast-model attempt falls back to the step model with the cache-safe request.
- **Soft trigger at a turn boundary.** Before the first model call of a message turn, when the step model has a cached input price and the context is at least 30 % of the window, the agent compacts early if the turn is expected to cost more without a compaction than with one. With `C` the tokens the summary would replace, `S` the expected summary size (the average of the session's earlier summaries, 2,000 tokens when there are none), `N` the average step calls per turn of the session (8 when there are none), `p` and `q` the step model's input and cached input prices and `k` the estimated cost of the compaction call chosen as above:
  - without compaction the turn reads `C` on every call: `C·r + (N - 1)·C·q`, where `r` is `q` when the cache is warm and `p` after an idle gap longer than the cache lifetime (the time since the session's last step or compaction call, property `pointer.context.cache-lifetime`, default 5 minutes);
  - with compaction it pays the call, one cold write of the summary and cached reads of it: `k + S·p + (N - 1)·S·q`;
  - compaction happens when the first is larger. With a warm cache this is the spec's `N·(C - S)·q > k + S·(p - q)`. System prompt and tools are cached in both cases and cancel out.
- **Hard limit unchanged.** Compaction at 80 % of the window (95 % for the `masking` strategy) still runs before any model call, also inside a turn. The soft trigger does not apply to `masking`, whose point is to postpone the summary, and can be switched off with `pointer.context.soft-compaction=false`, which the benchmark uses for the comparison run.
- **Earlier summaries are carried word for word.** When the covered items start with an earlier summary, the model is asked to summarize only what came after it, and the new summary item stores the earlier summary followed by the new part. A summary is therefore written once and never summarized again, as long as the carried text stays under 20 % of the window; beyond that the whole history is summarized afresh as before. The soft trigger counts the carried text as part of the context that remains after the compaction.
- **Visible trigger.** A completed `compaction_summary` item records `trigger`: `manual`, `limit` or `soft`. The usage ledger records the cache-safe call under purpose `compaction` but measures its prefix reuse against the session's previous step call, which it extends.
- Unchanged from ADR 0009: the current turn is never summarized, context assembly starts from the latest completed summary, manual compaction is a turn of kind `compaction`, and the original items stay in the database and the client.

## Consequences

- On a provider with prompt caching, a compaction on the session model costs roughly the cached price of the conversation plus the summary, instead of the full input price; the ledger shows this as high prefix reuse on compaction rows.
- The summary of a cache-safe compaction is written by the session model with the agent's own instructions in front, which is the quality ADR 0009 originally chose; the fast model is still used when it is cheaper, for example when nothing is cached.
- Sessions on priced, cached models compact earlier and more often than at 80 %; each compaction loses detail, which the benchmark measures with recall probes.
- The estimates use the four-characters-per-token estimate and the session's own averages, so the decision can be wrong for an unusual turn; the hard limit still protects the window.
- Calls whose provider reports no usage leave no ledger row and are missing from `N`; such providers usually have no prices either, and then the soft trigger never runs.
- A tool call in a cache-safe answer costs one wasted call before the retry.
- The first benchmark run on a free Kilo Gateway model showed why summaries are carried: with summaries of a summary, a constraint stated in the first turn survived two compactions and was gone after the third, both with and without the soft trigger, and the soft trigger made it happen sooner by compacting four times instead of twice. Carried summaries grow by one summary per compaction until the 20 % limit.

## Rejected alternatives

- **Keeping the separate summarizer request on the step model.** Simpler, but it pays the full input price for the conversation that the cache already holds.
- **Sending `tool_choice: "none"` instead of forbidding tools in the instruction.** It would enforce the rule, but it is a request field that some OpenAI-compatible servers render into the prompt or reject, and its effect on the cached prefix is not specified.
- **A fixed earlier threshold (for example 50 %).** It ignores prices and cache state: with a cheap cached price a long context is fine, and without one an early compaction is often a pure loss.
- **Only limiting how often the soft trigger fires.** It would delay the loss but not prevent it: the hard limit alone also lost the first-turn constraint after three compactions.
- **Evaluating the soft trigger before every call.** Compacting inside a turn would summarize the tool chain the model is working on, which ADR 0009 rules out.
