# 0026. Trajectory-aware model escalation

Status: Accepted. Builds on [ADR 0020](0020-model-roles-and-usage-ledger.md) (model roles) and [ADR 0021](0021-model-failover.md) (failover).

## Context

With smart routing the agent's steps still run on the session model; the cheap `fast` model only writes titles, compactions and memory notes. Many turns are easy enough for the fast model, but a fixed choice either pays the strong model's price on every turn or accepts the weak model's failures on the hard ones. The trajectory of a turn already shows when the agent is in trouble: it repeats calls, collects errors, edits the same file again and again or calls tools that do not exist. Those signs can be read from the stored items without asking any model. Feature spec: [F6](../features/f6-trajectory-escalation.md) (ESC-1 to ESC-5).

## Decision

- **A session flag `escalation`** (`off` or `on`, default from `user_settings.escalation_default`, itself `off`). It takes effect only with smart routing and outside Plan mode.
- **Fast first, one way.** With the flag on, a message turn starts on the routing plan's `fast` model and moves to the step model (the session model, or the planning model where that applies) when the turn escalates. It stays there until the turn ends; the next turn starts on the fast model again. There is no de-escalation.
- **Pre-checks.** The turn starts on the step model instead when the fast model is the same model, does not support tools, lacks vision while the turn's message has images, or when the session's context is at or above 80 % of the fast model's window. The same 80 % check before every later step escalates with reason `context`, so the fast model never gets a context it cannot hold. None of these raise an error.
- **Struggle signals after every step.** After the tools of a step have run, `StruggleSignals` computes six values from the turn's top-level tool calls (`repeat_call`, `error_streak`, `edit_thrash`, `malformed_call`, `denied`, `read_only_streak`) and `EscalationPolicy` turns them into a score: every step in which a signal fired adds the signal's weight, halved for each step since. Every step's values and score are stored in `step_signals`, with or without escalation, so policies can be recomputed offline.
- **Events and states.** Counters (`repeat_call`, `malformed_call`, `denied`, `check_failed`) fire in the step where they grow; states (`error_streak`, `edit_thrash`, `read_only_streak`) fire in every step they hold. Counting a counter in every later step made one invalid call push the score over the threshold two steps later without anything new happening, which an integration test of the nudge policy exposed.
- **Threshold.** A turn on the fast model escalates once the score reaches `pointer.escalation.threshold` (default 1.5). With the provisional weight of 1.0 per signal this means two different signals at once, or one signal in two consecutive steps. Weights (`pointer.escalation.weight.<signal>`) and the threshold are calibrated on recorded data in phase 3.
- **Nudge, an experiment.** With `pointer.escalation.policy=nudge`, the first crossing of `pointer.escalation.nudge-threshold` (default 1.0) on the fast model appends a `model_nudged` item whose text names what went wrong and asks for another approach; it is rendered into the context as a user message, so the context stays append-only. Only the escalation threshold moves the turn. Decisions are taken only after a step that continues the turn.
- **A failed check lowers the bar.** A follow-up that issue automation sends after a failing check carries `origin: check_failed` on its user message, and the `check_failed` signal fires in its first step with weight 1.0: the fast model gets one try with the check output, and any further sign of struggle escalates. An automation can turn escalation on for its runs (`automations.escalation`).
- **Visible switch.** An escalation appends a `model_escalated` notice item (`from`, `to`, `reason` `struggle` or `context`, `score`, `signals` that fired in the turn), and the step's `step_signals` row has `escalated` true. Steps on the fast tier are recorded in the ledger with role `fast` and purpose `step`, steps after escalation with the step model's role.
- **Unchanged around it.** The whole conversation goes to the escalated model, whose prompt cache is cold for that session; failover wraps whichever tier is current; the budget check runs before every call; compaction keeps using the step model and its window, so its threshold does not change with the tier.

## Consequences

- An easy turn costs the fast model's price; a hard one costs the fast model's attempts up to the escalation plus the strong model's rest of the turn, including one cold read of the context. Phase 3 measures whether that beats the fixed policies.
- Detection adds no model calls; it reads the turn's items once per step.
- Live check: on Experiential Labs with `gpt-6-luna` as the fast model and `deepseek-v4.1-flash` as the session model, a lookup turn stayed on the fast model for all three steps, and a turn that read a missing file three times with the same path escalated after its third step (score 2.5: repeated call and error streak); the session model then listed the directory and answered.
- Live check of `nudge` on the same pair: after the note, the fast model stopped re-reading the missing file (although the user's message asked for more attempts), listed the directory, searched for the name and answered correctly, so the turn never needed the session model. The note can override an explicit instruction to repeat, which is the intended trade-off but worth knowing.
- Heuristic signals can miss struggle that shows only in the text (a wrong answer given confidently) and can fire on legitimate repetition (re-reading a file after an edit). The signal ablation in phase 3 measures both.
- A cache-safe compaction ([ADR 0025](0025-cache-aware-compaction.md)) in a session whose steps ran on the fast model finds no warm prefix on the step model; its cost estimate treats the cache as warm by the time of the last call, which is an overestimate of the saving in that case.

## Rejected alternatives

- **A judge model reading the trajectory.** More general, but an extra model call per step costs more than the escalation is meant to save.
- **Choosing the model per turn up front from the user's message.** It cannot react to what happens during the turn, and a short message often hides a hard task.
- **De-escalating after clean steps.** It could save more on long turns, but every switch also costs a cold cache read; the benchmark records how long escalated turns continue before this is reconsidered.
- **Escalating on the first firing signal.** Single signals are common in healthy turns (one failed command, one re-read); requiring a repeat or a second signal keeps the cheap tier for those.
