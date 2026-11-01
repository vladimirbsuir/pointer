# 0009. Context compaction and plan approval

Status: Accepted.

## Context

Long sessions outgrow the model's context window (CTX-4), and providers differ in how they report it: some models have a known window, others have none.
Plan mode (MODE-2) needs a structured plan the user can approve. Asking the model for a Markdown plan in plain text leaves no reliable way to know when a plan is ready or what its steps are.
Both features change what the model sees, so they share the context assembly rules in [02-architecture.md](../02-architecture.md#43-context-assembly).

## Decision

### Compaction

- A compaction produces a `compaction_summary` item written by the session's own model from the conversation it replaces. The prompt asks for goals, decisions, files, commands with outcomes, current state and remaining work, keeping paths and error messages exact.
- Context assembly starts from the latest completed summary. Items of the summary's own turn that come before it stay in context, so a summary written at the start of a turn keeps the user message that started it.
- A summary covers whole earlier turns only. The current turn is never summarized, because its tool call chain is what the model is working on.
- Automatic compaction runs before a model call when the last known context size reaches 80% of the model's window. When the model has no window configured, 128,000 tokens is assumed.
- Manual compaction (`POST /sessions/{id}/compact`) runs as a turn of kind `compaction` while the session is idle, so it gets status changes, streaming, cancellation and error reporting like any other turn.
- After a compaction the context size is estimated at four characters per token until the provider reports real usage on the next call. Providers that never report usage use the same estimate.
- Original items stay in the database and in the client history. Only the model's view changes.

### Plan approval

- In Plan mode the model gets a server-side tool `propose_plan` with `summary` and `steps` (`title`, optional `details`). It is not part of the tool registry and never reaches the executor.
- A valid call creates a `plan` item with `approval: pending` and ends the turn. The item takes the place of the tool call in the model's history, with a result telling the model to wait for the user.
- Approving (`POST /sessions/{id}/plans/{itemId}/approve`) marks the plan `approved`, switches the session to Agent mode and starts a turn with a short user message that refers to the plan.
- Any other user message marks pending plans `rejected`: the user is asking for changes, and the model answers with a new plan.

## Consequences

- Compaction works with every OpenAI-compatible provider, including those without usage data, at the cost of an extra model call per compaction.
- A single turn whose tool results alone exceed the window still fails with the provider's error. Truncating old tool results inside a turn is left for later.
- Models without tool calling cannot propose structured plans; in Plan mode they answer in plain text.
- The plan is part of the history, so the implementing turn sees it without copying it into the user message.

## Rejected alternatives

- **Sliding window without a summary.** Cheap, but silently drops early requirements, which is the most common source of agent drift in long sessions.
- **Summarizing with a separate cheap model.** Requires a second model choice per user and gives worse summaries with local models.
- **Parsing a Markdown plan from the assistant's text.** Fragile across models and languages, and gives no clear moment when the plan is ready for approval.
