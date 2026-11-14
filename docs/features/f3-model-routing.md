# F3. Smart model routing

Status: Approved · Requirement ids: ROUTE-1 to ROUTE-6 · Depends on: providers and models (PROV-*), cost estimates (CTX-6).

## Goal

An optional per-session mode that sends each model call to the cheapest model that can do it: auxiliary calls and code exploration to a fast model, planning to a strong model, the main agent loop to the session model, with fallback to another provider when one fails. With routing off, behaviour is exactly as today. A session budget limit works in both modes.

## Builds on

- `agent/AgentRunner.runTurn`: one `ResolvedModel` per turn from `providerService.resolve(userId, context.modelId(), executorDeviceId)`; the same model is used by `Summarizer` (compaction) and `generateTitle`.
- `ResolvedModel.cost(prompt, completion)`, `SessionWriter.recordUsage`, session totals (V4, V12).
- `agent/ToolCallHandler` and the tool registry for a new server-side tool; read-only tool kinds from the registry.
- `user/SettingsService` (`defaultModelId`, `embeddingModelId`).
- `provider/llm/LlmException.code()`.

## Requirements

| Id | Requirement |
|---|---|
| ROUTE-1 | Routing is a session setting `off` or `smart`; new sessions take the default from user settings, which is `off`. |
| ROUTE-2 | Settings define model roles: `fast` (required for smart routing) and `plan` (optional). The session model is the `main` role. |
| ROUTE-3 | With smart routing, title generation, compaction summaries and memory extraction ([F4](f4-agent-memory.md)) use the `fast` model; Plan-mode turns use the `plan` model when set. |
| ROUTE-4 | With smart routing, the main model gets an `explore` tool that runs a read-only sub-agent on the `fast` model and returns its report. |
| ROUTE-5 | With smart routing, a model call failing before the first token with a retryable error is retried once and then sent to the next model in the model's fallback list. |
| ROUTE-6 | A session can have a budget in USD; when the session cost reaches it the running turn stops with reason `budget_exceeded`. Usage and cost are recorded per model and shown per session. |

## Design

### Data model (`Vn__model_routing.sql`)

```sql
ALTER TABLE user_settings
    ADD COLUMN routing_default text NOT NULL DEFAULT 'off' CHECK (routing_default IN ('off', 'smart')),
    ADD COLUMN fast_model_id uuid REFERENCES models (id) ON DELETE SET NULL,
    ADD COLUMN plan_model_id uuid REFERENCES models (id) ON DELETE SET NULL;

ALTER TABLE sessions
    ADD COLUMN routing text NOT NULL DEFAULT 'off' CHECK (routing IN ('off', 'smart')),
    ADD COLUMN budget_usd numeric(10, 4);

CREATE TABLE model_fallbacks (
    model_id          uuid NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    position          integer NOT NULL,
    fallback_model_id uuid NOT NULL REFERENCES models (id) ON DELETE CASCADE,
    PRIMARY KEY (model_id, position)
);

CREATE TABLE model_usage (
    id                uuid PRIMARY KEY,
    session_id        uuid NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id           uuid NOT NULL,
    model_id          uuid REFERENCES models (id) ON DELETE SET NULL,
    model_label       text NOT NULL,
    role              text NOT NULL CHECK (role IN ('main', 'plan', 'fast', 'explore', 'fallback')),
    purpose           text NOT NULL CHECK (purpose IN ('step', 'title', 'compaction', 'memory', 'explore')),
    prompt_tokens     bigint NOT NULL,
    completion_tokens bigint NOT NULL,
    cost_usd          numeric(12, 6) NOT NULL,
    created_at        timestamptz NOT NULL
);

CREATE INDEX model_usage_session_idx ON model_usage (session_id, created_at);
```

`model_usage` is written in both routing modes; session totals stay as they are.

### Backend: `agent/routing`

| Class | Responsibility |
|---|---|
| `ModelRouter` | `ModelPlan plan(userId, session, executorDeviceId)` → resolved models per role. With `off` every role is the session model and no fallbacks. Local models are skipped for server sessions (same rule as [ADR 0016](../adr/0016-remote-execution-in-containers.md)). |
| `RoutedClient` | Wraps `OpenAiCompatibleClient.stream`: picks the model for a purpose, applies retry and fallback, records `model_usage`, checks the budget. |
| `ExploreTool` | Server-side handling of `explore` in `AgentRunner` (like `PlanTool`/`ProposeTool`): nested loop with the `fast` model. |
| `BudgetGuard` | Reads session cost plus the current step's estimate; throws `BudgetExceeded`, mapped to turn reason `budget_exceeded`. |

`AgentRunner.runTurn` changes: replace the single `model` with a `ModelPlan`; main steps use `plan` role in Plan mode when set, otherwise `main`; `Summarizer` and `generateTitle` take `plan.fast()` (equal to main when routing is off). `recordUsage` gets the role and purpose. New constant `Turn.BUDGET_EXCEEDED`.

### Explore sub-agent

- Tool schema: `{task: string, paths?: string[]}`; description tells the model to delegate broad reading and searching and to expect a summary with paths and line numbers.
- Loop: system prompt "read-only investigator", tools = registry tools of kind `read` available in the session (list, read, glob, grep, semantic_search, read-only MCP tools excluded), max 15 steps, max context 60% of the fast model's window, cancellation shared with the turn.
- Tool calls run through `ToolCallHandler` with the same permissions; they are stored as items with `parent_item_id` = the `explore` tool call item (new nullable column on `items`), so the desktop shows them nested and collapsed.
- Result: the sub-agent's final message, truncated to 8,000 characters, returned as the tool result to the main model.
- Not offered when the fast model does not support tools.

### Fallback

- Retryable: HTTP 408, 429, 5xx, connect or read timeout before the first token, relay unavailable. Not retryable: 4xx other than those, failures after streaming started.
- Order: same model once after 2 s (or `Retry-After` up to 20 s), then each fallback in `position` order. Fallbacks must support tools if the request has tools.
- A switch appends a notice item `model_switched` `{from, to, reason}` to the turn; the session model is not changed.

### API and protocol

- Settings DTO: `routingDefault`, `fastModelId`, `planModelId`.
- Session create/update DTO: `routing`, `budgetUsd`. Session response: `routing`, `budgetUsd`, `costUsd`.
- `GET /api/v1/models/{modelId}/fallbacks`, `PUT` the ordered list.
- `GET /api/v1/sessions/{sessionId}/usage` → rows grouped by model and purpose.
- New item types `model_switched` and nested items via `parentItemId`; turn reason `budget_exceeded`. Update [03-protocol.md](../03-protocol.md).

### Desktop

- Settings → Models: Fast model, Plan model, "Smart routing for new sessions" toggle, fallback list editor in the model dialog.
- Composer model picker: routing toggle (`Smart` badge) and budget field in the session settings popover.
- Usage popover: cost per model and purpose; `explore` calls rendered as a collapsible group; `model_switched` as an inline notice.

### Telegram

- Final answer footer shows cost; `budget_exceeded` is reported like other turn failures.

## Phases

1. `model_usage`, per-model usage view, session budget (useful on its own and for [F1](f1-issue-to-pr.md)).
2. Roles and routing of auxiliary calls and Plan-mode turns.
3. Fallback lists and retry.
4. `explore` sub-agent with nested items.

## Tests

- `ModelRouter`: off vs smart, missing fast model, local models on server sessions.
- `RoutedClient` with a scripted fake LLM: retry then success, fallback chain, no fallback after streaming started, tool support filter, usage rows.
- `BudgetGuard`: stops at the limit, reason recorded, events emitted.
- `ExploreTool`: read-only tool set, step limit, cancellation, nested item ids, truncation.
- `AgentRunner` regression: routing `off` produces the same requests as before.

## Thesis metrics

- Same task set (for example the F1 issue set) with routing off and smart: total cost, share of tokens on the main model, success rate, duration.
- Main-model context growth with and without `explore` (prompt tokens per step).
- Fallback: turns completed despite a provider outage simulated with an invalid endpoint.

## Open questions

- Whether routing of Agent-mode steps themselves (for example read-only steps to the fast model) is worth it after measuring `explore`; not planned now.
