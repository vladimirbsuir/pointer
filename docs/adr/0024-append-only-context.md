# 0024. Append-only context with per-turn snapshots

Status: Accepted. Amends where [ADR 0019](0019-agent-memory.md) places the memory index.

## Context

Providers serve the repeated beginning of a request from a prompt cache at a fraction of the input price, but only as long as the beginning is byte-for-byte identical to an earlier request. The agent rebuilt the system prompt on every call with the date, the git branch and the memory index in it. The [prompt cache baseline](../benchmarks/cache.md) showed what that costs: when the memory index exceeds its budget it is ordered by relevance to each new message, so the system prompt changed every turn and the reusable share of the main loop in a twelve-turn session fell from 0.95 to 0.74. Feature spec: [F5](../features/f5-cache-aware-context.md) (CACHE-3, CACHE-6).

## Decision

- **The system prompt holds only what the user edits**: base and mode instructions, OS, shell, project name, global instructions and project rules.
- **Volatile context is a per-turn snapshot.** At the start of a message turn the agent loop renders the date, the git branch and the memory section into a snapshot and stores it in `turns.context_snapshot` only when it differs from the latest snapshot of an earlier, not rewound turn. The turn's first user message is rendered with its snapshot in front, inside a `<context>` block that says it replaces earlier ones. Because the snapshot is stored, the same items always render to the same messages.
- **Compaction carries the snapshot.** In front of the summary message goes the latest snapshot of the turns the summary replaced, unless the first remaining turn has its own. Both inputs are fixed between compactions, so the summary message does not change when a later turn gains a snapshot.
- **Deterministic tools.** Registry tools come in name order from the database, and the server-side tools (`propose_plan`, `explore`, `propose_tool`) follow in a fixed order, as before. Tool JSON schemas keep insertion order where the code builds them in order and are sorted by key where they come from unordered maps (`Map.of` iterates in a different order after every JVM start), so a restart does not change the serialization.
- **Optional cache key.** A provider can be marked to receive `prompt_cache_key` with the session id, which OpenAI-style caches use to route a session's requests to the same cache. It is off by default because not every OpenAI-compatible server accepts unknown fields.
- **Invariant.** Between compactions, the request sent at every model call is a strict extension of the request sent at the previous call of the session; a property test checks it over generated conversations.

## Consequences

- A turn whose snapshot is unchanged adds nothing to the context, and a changed one adds a cold block at the end instead of invalidating the whole history.
- Every change of the memory index stays in the conversation until the next compaction, so a session whose index changes every turn grows by one index per turn. The index is limited to about 6,000 characters, and the benchmark measures whether the growth outweighs the cache it saves.
- Measured on the same twelve-turn session: with the memory index reordered every turn the cached share rose from 0.73 to 0.96, and all twelve snapshots together added about 18,000 tokens to the context.
- A change of mode still changes the system prompt and the tool list and resets the cache.
- The memory index is no longer in the system prompt; MEM-2 is restated as "every turn's context".

## Rejected alternatives

- **Keeping volatile context at the end of the system prompt.** The system prompt is the first thing in the request, so any change there invalidates everything after it.
- **Appending a fresh context block to the last message of every call.** It keeps the prefix stable too, but the block would be sent and paid for on every call instead of once per change, and the history would not show what context a turn ran with.
- **Sorting every tool by name.** Equally deterministic, but in Ask mode it moved `explore` to the top of the list, and in two benchmark sessions the model then delegated six to seven times as often as before. Tool order is part of agent behaviour, so the cache work keeps it unchanged.
- **Freezing the memory index for the whole session.** Stable, but memories saved during the session and the relevance order for later questions would never reach the model.
