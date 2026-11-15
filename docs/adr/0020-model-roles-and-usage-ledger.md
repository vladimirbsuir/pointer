# 0020. Model roles and usage ledger

Status: Accepted.

## Context

A session used one model for everything: agent steps, Plan mode, titles, compaction and later memory curation. Cheap calls ran on expensive models, and spending was visible only as one session total without a limit. Feature spec: [F3](../features/f3-model-routing.md).

## Decision

- **Usage ledger.** Every model call with reported usage is written to `model_usage` with the model, role (`main`, `plan`, `fast`), purpose (`step`, `title`, `compaction`, `memory`) and cost. `GET /sessions/{id}/usage` groups it.
- **Session budget.** A session can have a budget in USD; before each agent step the loop compares the session's estimated cost of steps and compaction with it and ends the turn with `budget_exceeded`. Without model prices the budget does not apply.
- **Roles instead of per-call configuration.** Users mark one fast model and one planning model. Sessions have routing `off` (default) or `smart`; with `smart`, Plan-mode steps use the planning model and titles, compaction and memory curation use the fast model. `ModelRouter` lives in its own `routing` package because both the agent loop and memory curation use it.
- **Safe fallbacks.** A role model that is not set or cannot be resolved falls back to the session model; compaction takes the fast model only when its context window holds the conversation, and retries with the step model when the fast model fails.

## Consequences

- Routing is opt-in, so sessions without it behave exactly as before.
- The ledger also covers calls outside turns (memory curation), so total spending per session is visible even where the budget does not count it.
- Fallback lists across providers and an exploration sub-agent on the fast model remain open phases of F3.

## Rejected alternatives

- **Routing individual agent steps by heuristics.** Read-only steps could go to a cheaper model, but a wrong guess degrades the agent; delegating exploration to a sub-agent is the planned alternative.
- **Budgets per user or per month.** Useful later; a session budget is the unit the user watches in the UI.
