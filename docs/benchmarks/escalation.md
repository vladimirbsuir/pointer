# Escalation benchmark

Measures trajectory-aware escalation ([F6](../features/f6-trajectory-escalation.md), ESC-6): how much of the strong model's success rate a fast-first cascade keeps, at what share of its cost, and how well each struggle signal predicts a failed turn. Status: the harness and the offline evaluation are ready and smoke-tested; the calibration runs are open.

## Setup

- **Tasks.** [`infra/bench/escalation/fixture`](../../infra/bench/escalation/fixture/) is a small Python project (`shop`: pricing, carts, stock, dates, reports). Six coding tasks need a change that makes a `unittest` module pass while `tests/test_baseline.py` keeps passing: `slugify` and `discount` (easy, one function), `business_days` and `cart_tiers` (medium; the cart bug is in `pricing.py`, not in the cart), `reserve_atomic` and `monthly_summary` (hard; the summary needs a change in two modules). Two lookup tasks ask where something is defined and are checked by the answer naming the file and symbol. [`reference/`](../../infra/bench/escalation/reference/) holds working solutions; `run.py --verify-fixture` checks that every task fails on the fixture and passes with the reference.
- **Harness.** [`run.py`](../../infra/bench/escalation/run.py) creates an `escalation-fixture` project, starts its own bench device on a scratch workspace, and for every task resets the workspace, opens an Agent-mode session with auto-approve, sends the task, waits for the turn and checks the result after restoring the tests (so a model cannot pass by editing them). Policies: `fast` (the fast model as session model), `main` (the session model alone) and `escalation` (smart routing with escalation; the backend's `pointer.escalation.*` properties decide threshold and policy, so a run's `--label` should name them). Runs that end in a provider error are rerun once after a pause and otherwise excluded.
- **Evaluation.** [`evaluate.py`](../../infra/bench/escalation/evaluate.py) prints success, cost (from `--prices model=input/output`), tokens, duration and escalation share per run label, success by difficulty, and from the fast-only runs the offline replay: for every threshold the share of turns that would escalate, of failed turns caught and of successful turns escalated needlessly, plus the AUC of each signal and of the full score for a failed turn. Its scoring is the backend's (counters fire when they grow, states while they hold, halving per step).

## Smoke run

Experiential Labs, `gpt-6-luna` as the fast model and `deepseek-v4.1-flash` as the session model, default threshold 1.5, 2026-11-18.

| Label | Policy | Runs (errors) | Success | Steps | Escalated |
|---|---|---|---|---|---|
| smoke-fast | fast | 1 (2) | 1.00 | 5 | 0 |
| smoke-escalation | escalation | 3 (0) | 1.00 | 2 to 9 | 0 |

The two errors were the provider's limit of 10 requests per minute, which led to the automatic rerun. `gpt-6-luna` solved `slugify`, `cart_tiers`, `monthly_summary` and `stock_lookup` without a single firing signal, so this pair cannot calibrate anything: the cascade only pays off when the fast model is clearly weaker than the session model.

Live checks of the mechanisms on the same pair, outside the harness: with `escalate`, a turn told to re-read a missing file escalated after its third step (score 2.5: repeated call and error streak) and the session model answered; with `nudge`, the same turn received the note after its second step, stopped repeating, searched for the file and answered on the fast model.

## To run the calibration

1. Build the bench device and check the fixture: `python infra/bench/escalation/run.py --verify-fixture`.
2. With the backend running (default properties for `escalate`, `--pointer.escalation.policy=nudge` for the nudge runs, `--pointer.escalation.threshold=<t>` for other thresholds) and the benchmark account loaded, run every policy with a weak fast model, for example:

   ```bash
   python infra/bench/escalation/run.py --policy fast --main-model <strong> --fast-model <weak> --repeat 3 --label fast
   python infra/bench/escalation/run.py --policy main --main-model <strong> --fast-model <weak> --repeat 3 --label main
   python infra/bench/escalation/run.py --policy escalation --main-model <strong> --fast-model <weak> --repeat 3 --label escalate-1.5
   ```

3. Summarize: `python infra/bench/escalation/evaluate.py --prices "<weak>=0.1/0.4,<strong>=0.5/2"`.

A full matrix is 8 tasks × 3 runs × 4 to 5 labels, about 100 to 120 turns of 2 to 15 calls each, which exceeds the free plans used so far in one day; Kilo Gateway's free models (including 2B-class ones) are the candidates.

## Threats to validity

- The fixture is small and synthetic, written for this benchmark; tasks of a real repository would show other failure patterns.
- The lookup checks look for substrings in the answer and can be fooled by an answer that lists many files.
- One provider's rate limits change the timing of steps but not their content; reruns after a provider error repeat the task from a clean workspace.
