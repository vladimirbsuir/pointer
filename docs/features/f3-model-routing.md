# F3. Smart model routing

Status: Implemented ([ADR 0020](../adr/0020-model-roles-and-usage-ledger.md), [ADR 0021](../adr/0021-model-failover.md), [ADR 0022](../adr/0022-explore-sub-agent.md)); the thesis measurements are open · Requirement ids: ROUTE-1 to ROUTE-6 · Depends on: providers and models (PROV-*), cost estimates (CTX-6).

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
| `ModelRouter` | `ModelPlan plan(userId, smart, mainModel, mode, executorDeviceId)` → routed models per role (`main`, `step`, `fast`). With `off` every role is the session model. A role model that cannot be resolved falls back to the session model; local models are resolved like the session model, and a compaction that fails on them is retried with the step model. |
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

1. `model_usage`, per-model usage view, session budget (useful on its own and for [F1](f1-issue-to-pr.md)). Implemented: migration V22 (`sessions.budget_usd`, `model_usage` with a nullable `turn_id`, the `budget_exceeded` turn status), `session/UsageLedger` writing a row for every call with reported usage (agent steps, titles, compaction, memory curation), `GET /sessions/{id}/usage`, `budgetUsd` on session create and update (0 removes it). The budget is checked before every agent step against `sessions.estimated_cost`, which counts steps and compaction; titles and memory curation appear in the ledger but not in the budget. Without model prices the cost is unknown and the budget does not apply. The desktop opens a usage dialog from the header indicator (table by model and purpose, budget field) and shows the spent amount against the budget; Telegram reports `budget_exceeded` as a stop. Checked live with `glm-5.3-flash` and test prices: a budget below the cost of the first turn stopped the second turn before any model call.
2. Roles and routing of auxiliary calls and Plan-mode turns. Implemented: migration V23 (`user_settings.routing_default`, `fast_model_id`, `plan_model_id`; `sessions.routing`), `routing/ModelRouter` (its own package because both `agent` and `memory` use it). With `smart` routing, Plan-mode steps use the plan model when it is set and supports tools; titles, compaction and memory curation use the fast model. Compaction takes the fast model only when its context window holds the conversation with a 20 % margin, and retries with the step model when the fast model fails before streaming. A role model that is not set or cannot be resolved falls back to the session model. `model_usage.role` records `main`, `plan` or `fast`. The desktop marks the fast and planning models with buttons in the model list, shows them with the default switch at the top of Settings → Providers, and has a Smart switch in the session header. Checked live: a Plan-mode session on `deepseek-v4.1-flash` with smart routing ran its six steps on `gpt-6-luna` (plan role) and generated the title with `glm-5.3-flash` (fast role).
3. Fallback lists and retry. Implemented: migration V24 (`model_fallbacks`, the `model_switched` item type), `GET`/`PUT /models/{id}/fallbacks` (at most 5 of the user's own models) and `fallbackModelIds` on models, `routing/RoutedClient`. With `smart` routing every model call (agent steps, compaction, titles, memory curation) that fails before its first token with HTTP 408, 429, 5xx, an unreachable provider or an offline local provider is retried once on the same model after 2 s (`pointer.routing.retry-delay`, or `Retry-After` up to 20 s) and then sent to the model's fallbacks in order; fallbacks that cannot be resolved, lack tool support for a request with tools or lack vision for a request with images are skipped. Other errors and failures after streaming started are not retried. Each switch inside a turn appends a `model_switched` notice; the turn stays on the working model for its remaining steps and returns to the session model first if that model fails too; the next turn starts on the session model again. Usage of a fallback is recorded with role `fallback` and the fallback's prices. With routing `off` nothing is retried. The desktop edits the list in the model options and shows the notice inline in the conversation. Checked live: a session on a model behind an unreachable endpoint (`http://127.0.0.1:9/v1`) with the fallback list `glm-5.3-flash`, `gpt-6-luna` answered through `glm-5.3-flash` in 15 s with one notice, and the ledger recorded the step with role `fallback`.
4. `explore` sub-agent with nested items. Implemented: migration V25 (`items.parent_item_id`), `agent/ExploreAgent`. The tool is offered in every mode when routing is `smart`, a fast model is set and supports tools, and the session has file tools. The sub-agent gets its own system prompt and only the task and start paths, and may call `list_directory`, `read_file`, `glob`, `grep` and `semantic_search`; any other tool is denied. It makes at most 15 model calls; the last one, or the first after its context passes 60 % of the fast model's window, has no tools and asks for the report, which is cut to 8,000 characters. Its tool calls are stored with `parent_item_id` and stay out of the main context, compaction, memory curation and Telegram progress. Usage is recorded with role and purpose `explore` and counts toward the session cost and budget. A model error in the sub-agent fails only the tool call. Sub-agent text is not streamed; only its tool calls appear live. The desktop shows an Explore card with the nested calls and the report, collapsed by default and opened when a nested call waits for approval. Checked live in Ask mode: `deepseek-v4.1-flash` delegated a survey of the preview project to `glm-5.3-flash`, which made 8 tool calls in 5 model calls (6,150 prompt tokens) and returned a report; the main model used 3 calls (8,893 prompt tokens).

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
