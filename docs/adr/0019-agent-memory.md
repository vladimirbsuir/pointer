# 0019. Agent memory

Status: Accepted.

## Context

Preferences and corrections stated in one session were lost in the next. Project rules (`AGENTS.md`) are shared and versioned, so they are the wrong place for personal preferences, and they are not available to Telegram or server sessions without the repository. Feature spec: [F4](../features/f4-agent-memory.md).

## Decision

- **Memory lives on the backend.** A `memory` module stores typed memories (`user`, `feedback`, `project`, `reference`) per user, either for all projects or for one project, with revisions for undo. It reaches every client and executor: desktop, Telegram and server sessions.
- **Index in the prompt, details on demand.** Every turn's system prompt lists the visible memories as one line each within about 6,000 characters, pinned memories in full; the agent reads, saves, searches and deletes memories with server-side tools (`ServerOperation` beans). The index is reordered by embedding similarity only when it does not fit.
- **The agent saves without asking, the user can undo.** `memory_save` and `memory_delete` are allowed by default; deletion is a status change and every change has an Undo action in the conversation.
- **Curation after sessions.** A scheduled job takes idle or archived sessions with at least three user messages, active within the last three days, and makes one model call that returns a session summary and up to five suggested memories. Suggestions wait for the user's decision; summaries are searchable with `search_sessions`.

## Consequences

- In the benchmark ([benchmarks/memory.md](../benchmarks/memory.md)) preferences were followed in new sessions in 10 of 10 cases with memory against 4 of 10 without; ten memories cost about 270 prompt tokens.
- Curation spends tokens of the user's provider after a session ends; it can be turned off with `pointer.memory.curation-enabled` and is recorded in the usage ledger.
- Memory content leaves the device like conversation content already does.

## Rejected alternatives

- **Memory files in the repository.** Shared with the team and versioned, but not personal and unavailable without the checkout.
- **Embedding every memory into the prompt.** Simple, but the prompt grows with every memory.
- **Extraction after every turn.** More precise timing, but a model call per turn.
