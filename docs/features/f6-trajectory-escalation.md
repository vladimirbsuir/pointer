# F6. Trajectory-aware model escalation

Status: In progress (phases 1 and 2 implemented) · Requirement ids: ESC-1 to ESC-6 · Depends on: routing, usage ledger and failover ([F3](f3-model-routing.md), [ADR 0020](../adr/0020-model-roles-and-usage-ledger.md), [ADR 0021](../adr/0021-model-failover.md)). Optional: cached-token accounting from [F5](f5-cache-aware-context.md) phase 1, the check loop of [F1](f1-issue-to-pr.md).

## Goal

With escalation on, the agent's steps run on the cheap `fast` model and a turn moves to the session model as soon as its own trajectory shows trouble: repeated tool calls, a streak of errors, edits to the same file over and over, malformed tool calls, denied actions, a failed check. Detection reads only the items that are already stored, so it adds no model calls. The aim is the cost of the cheap model on easy tasks and the success rate of the strong model on hard ones, and a measured cost and success curve against the two fixed policies.

## Builds on

- F3: `ModelRouter.ModelPlan` (`main`, `step`, `fast`, `explore`), `RoutedClient.failover`, the `model_switched` notice item, `model_usage`, `AgentRunner.budgetError`.
- `AgentRunner.runTurn`: the step model is fixed for the whole turn (`routes.step().model()`) and the loop handles the tool calls of each step before the next call, which is where the decision is made.
- Items: `step`, `toolName`, `argumentsJson`, `toolStatus` (`succeeded`, `failed`, `denied`, `cancelled`), `error.code` (for example `invalid_arguments`), `fileChanges` (`ToolCallHandler.complete`).
- Settings and session flags in the style of `routing` (V23).

## Requirements

| Id | Requirement |
|---|---|
| ESC-1 | Escalation is a session setting `off` or `on`; new sessions take the default from user settings, which is `off`. It needs smart routing, a `fast` model that supports tools, and is not applied in Plan mode. |
| ESC-2 | With escalation on, the steps of a turn start on the `fast` model and, once the turn escalates, run on the session model until the turn ends. The next turn starts on the `fast` model again. A turn runs on the session model from the start when its context is over 80 % of the `fast` model's window. |
| ESC-3 | After every step the backend computes struggle signals from the turn's tool calls and results and a score; reaching the threshold escalates the turn. |
| ESC-4 | An escalation appends a `model_escalated` notice item with the model names, the score and the signals that caused it. |
| ESC-5 | The signals and the score of every step are recorded, and the usage view shows per turn which steps ran on which model. |
| ESC-6 | A benchmark compares fast only, session model only and escalation with several thresholds and policies on the same tasks and reports cost, success and the share of turns escalated; it evaluates each signal separately. |

## Design

### Data model (`Vn__trajectory_escalation.sql`)

```sql
ALTER TABLE user_settings
    ADD COLUMN escalation_default text NOT NULL DEFAULT 'off' CHECK (escalation_default IN ('off', 'on'));

ALTER TABLE sessions
    ADD COLUMN escalation text NOT NULL DEFAULT 'off' CHECK (escalation IN ('off', 'on'));

CREATE TABLE step_signals (
    id             uuid PRIMARY KEY,
    session_id     uuid NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id        uuid NOT NULL,
    step           integer NOT NULL,
    model_label    text NOT NULL,
    signals        jsonb NOT NULL,
    score          numeric(6, 3) NOT NULL,
    escalated      boolean NOT NULL,
    created_at     timestamptz NOT NULL
);

CREATE INDEX step_signals_session_idx ON step_signals (session_id, turn_id, step);
```

`signals` holds the value of each signal for the step, so the benchmark can recompute any score offline.

### Backend: `agent/escalation`

| Class | Responsibility |
|---|---|
| `StruggleSignals` | Pure function from the list of this turn's steps (tool name, normalized arguments, tool status, error code, files changed) to a map of signal values. |
| `EscalationPolicy` | Turns signals into a score with weights and decay, and decides `CONTINUE`, `NUDGE` or `ESCALATE` against thresholds. |
| `EscalationController` | Per turn: keeps the current tier, calls the policy after each step, records `step_signals`, appends the notice, and tells `AgentRunner` which `RoutedModel` to use for the next step. |

`AgentRunner.runTurn` changes: `model` and `routes.step()` become the controller's current tier for each step; the context window and tool support are read from that tier; the ledger gets role `fast` for steps on the cheap tier (purpose stays `step`) and `main` after escalation. With escalation off the controller always returns `routes.step()`, so behaviour is exactly as today.

### Signals

All are computed from the stored items of the current turn; a "window" is the last six steps.

| Signal | Fires when |
|---|---|
| `repeat_call` | The same tool with the same normalized arguments is called at least twice in the window. |
| `error_streak` | Three or more consecutive tool results have status `failed`. |
| `edit_thrash` | The same path is changed at least three times in the window. |
| `malformed_call` | A call has `error.code` `invalid_arguments` or names an unknown tool; counted per turn. |
| `denied` | The user denied a tool call in this turn. |
| `check_failed` | The turn is a follow-up that [F1](f1-issue-to-pr.md) sent after a failing check command. |
| `read_only_streak` | Eight or more consecutive steps without a change, in Agent mode. |

Arguments are normalized by sorting JSON keys and dropping whitespace. Each signal has a weight; the score is the sum of the weights of the signals that fire, with the contribution of a signal that fired in earlier steps halved per step. Weights, the window and the thresholds are application properties (`pointer.escalation.*`) with defaults chosen in phase 3 from the recorded data.

### Policy

- `escalate` (default): score at or above the threshold moves the turn to the session model for the rest of the turn.
- `nudge` (experiment, property `pointer.escalation.policy`): the first crossing of a lower threshold appends one system notice to the next step's request (the model sees "your last calls repeated or failed; change approach") and only a crossing of the higher threshold escalates. The benchmark decides whether a cheap retry beats escalating at once.
- Escalation is one-way within a turn. De-escalation after clean steps is not implemented.
- The switch keeps the whole conversation. The new model's prompt cache is cold, which [F5](f5-cache-aware-context.md) makes visible in the ledger; the cost of that is part of the measured cost of escalating.
- Failover ([ADR 0021](../adr/0021-model-failover.md)) wraps whichever tier is current, and the budget limit applies as before.

### Pre-checks (ESC-2)

At the start of a turn the controller runs on the session model, without escalation, when the `fast` model is not set, does not support tools, lacks vision for a message with images, or when `context_tokens` is above 80 % of its window; it does not raise an error in these cases.

### API and protocol

- Settings DTO: `escalationDefault`. Session create/update DTO and response: `escalation`.
- Item type `model_escalated` `{from, to, score, signals}`. Update [03-protocol.md](../03-protocol.md) and [04-data-model.md](../04-data-model.md).
- `GET /api/v1/sessions/{sessionId}/usage` rows are unchanged; `GET /api/v1/sessions/{sessionId}/turns/{turnId}/signals` returns the `step_signals` rows of a turn.

### Desktop

- Settings → Models: "Escalation for new sessions" next to the smart routing default.
- Session header popover: escalation switch, enabled only with smart routing and a fast model.
- Conversation: `model_escalated` as an inline notice with the signals that caused it, in the style of `model_switched`.
- Usage dialog: steps per model for each turn.

### Telegram

- A turn that escalated shows the notice in its progress; the cost footer is unchanged.

## Phases

1. Signals without switching (ESC-3, ESC-5): migration, `StruggleSignals`, `step_signals` recorded for every step of every turn, whatever the escalation setting (the setting arrives in phase 2), the signals endpoint. Models do not change, so this is safe to run on real sessions and produces the data for choosing weights. **Implemented**:
   - Migration `V31__step_signals.sql` creates only `step_signals`; the escalation columns of `user_settings` and `sessions` come with phase 2 in their own migration.
   - `agent/escalation`: `StruggleSignals` (pure function over the turn's top-level `tool_call` items; nested explore calls are ignored), `EscalationPolicy` (firing thresholds as in the signal table, provisional weight 1.0 per signal overridable with `pointer.escalation.weight.<signal>`, halving per step since a signal last fired), `StepSignalLog` and `EscalationController.Trajectory`, which records a row after every model call of a message turn that was not cancelled, including the final answer, with `escalated` false.
   - Denials became distinguishable, which `denied` and `malformed_call` need: a denied tool call carries `error.code` `denied` (the user), `blocked` (permission settings) or `tool_unavailable` (not offered in the mode or to the explore sub-agent, counted as malformed). Before, all three used `denied`.
   - `GET /sessions/{id}/turns/{turnId}/signals` returns a turn's rows. The usage view part of ESC-5 (steps per model) comes with phase 2, when steps can run on two models.
   - `check_failed` is left for phase 4 with F1.
2. Escalation (ESC-1, ESC-2, ESC-4): setting, controller, per-step model, notice item, pre-checks, desktop switch and notice. **Implemented** ([ADR 0026](../adr/0026-trajectory-aware-escalation.md)):
   - Migration `V32__escalation.sql`: `user_settings.escalation_default`, `sessions.escalation`, item type `model_escalated`.
   - `EscalationController.begin` runs the pre-checks and returns the turn's `Trajectory`; `AgentRunner` asks it for the tier before every step, so the request model, vision and failover follow the tier, while compaction and the context meter keep the step model. The 80 % check also runs before every later step and escalates with reason `context`.
   - The score now sums every firing of a signal with its decay (phase 1 counted only the latest firing, which would never let a single persistent signal reach a threshold above its weight). Threshold `pointer.escalation.threshold`, default 1.5, provisional until phase 3.
   - `model_escalated` carries `reason` (`struggle` or `context`) in addition to the spec's fields. The desktop shows the session switch next to smart routing (enabled with smart routing and a fast model), the default in Settings → Providers and the notice with the signals; Telegram adds the notice to the turn's progress message.
   - Live check on Experiential Labs (`gpt-6-luna` fast, `deepseek-v4.1-flash` session model): a lookup turn stayed on the fast model, and a turn that re-read a missing file escalated after its third step and was then answered by the session model.
   - Still open from ESC-5: the usage dialog shows steps per model and role for the whole session (role `fast` rows), not per turn; the per-turn view comes with the phase 3 tooling, which reads `step_signals`.
3. Calibration and policies (ESC-6): label the recorded steps by whether the fast model's turn ended in failure, fit weights and thresholds offline, compare `escalate` and `nudge`, set the defaults.
4. F1 integration: `check_failed` signal and the escalation flag in the automation settings.

## Tests

- `StruggleSignals` on scripted trajectories: each signal firing and not firing, window edges, argument normalization.
- `EscalationPolicy`: weights and decay, thresholds, `nudge` ordering.
- `EscalationController` with a scripted fake LLM: a turn that never struggles stays on the fast model, a turn that repeats a call escalates at the expected step, the next turn starts on the fast model, Plan mode and a missing fast model never escalate, a large context starts on the session model.
- `AgentRunner` regression: escalation `off` produces the same requests as before; ledger roles per step; failover still works on the escalated tier.
- Offline evaluation script reproduces a recorded run's decisions from `step_signals`.

## Thesis metrics

Task set: coding tasks with an automatic check, taken from this repository in the way of [F1](f1-issue-to-pr.md) (small changes with tests that fail before and pass after) plus the read-only lookup and trace tasks of the [routing benchmark](../benchmarks/routing.md); the harness is a new script in `infra/bench/escalation/`.

- Policies compared on the same tasks: fast model only, session model only, escalation at several thresholds, `nudge` then escalate. For each: success rate (check passes or expected files named), cost, total tokens, duration, share of turns escalated.
- Cost and success plane: one point per policy and threshold; the claim is the share of the strong model's success rate kept at the share of its cost, and where escalation dominates both fixed policies.
- Escalation timing: for escalated turns, the step of escalation, the tokens spent on the fast tier before it, and the share of escalated turns that the strong model then solved (the benefit) against turns escalated unnecessarily because the fast model would have finished (the waste).
- Missed escalations: turns that stayed on the fast model and failed, the main risk of any cascade.
- Signal ablation: for each signal alone and for the full score, how well the score at a step predicts that the turn will fail on the fast model (area under the ROC curve), computed offline from `step_signals`.

## Open questions

- Whether a learned predictor (for example logistic regression on the signal values) beats the hand-set weights enough to be worth including; it is evaluated offline in phase 3 and used only if the gain is clear.
- A judge model that reads the trajectory is the alternative to heuristic signals, at the price of an extra call per step; not planned, and mentioned in the thesis as related work.
- De-escalation after several clean steps could save more on long turns; the benchmark records how long escalated turns continue to see whether it matters.
- The weights depend on the pair of models; the defaults are fitted on the test models and may need refitting for others.
