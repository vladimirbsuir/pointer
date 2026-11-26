# Open work

What is left after the roadmap and the approved features ([features/README.md](features/README.md)), as of 2026-11-26. Each item says where it is and what done looks like. Remove an item when it is done, or move it to the relevant spec.

## Bugs

- **A result written into a silently dead connection is lost.** The core buffers an `exec.result` only when it cannot hand it to the socket ([realtime/mod.rs](../desktop/src-tauri/src/realtime/mod.rs), `undelivered`). When the network goes away without a close, the write succeeds locally, the result never arrives, and after the reconnect `hello.undeliveredResults` is empty. Found in the live check with `infra/bench/live/result_delivery.py stall`: the command succeeded, and two minutes later (its timeout plus the grace period) the backend told the model `timeout: The device did not respond in time`. A hard disconnect works: the result is buffered and arrives in the next `hello`. Done: the core keeps the results it sent during the last two read timeouts and adds them to `undeliveredResults` after a lost connection (the backend ignores results of calls that are already finished, so a repeat is harmless); a test in the core and the `stall` run of the script.

## Measurements and runs still open

- **F5 phase 4** ([f5-cache-aware-context.md](features/f5-cache-aware-context.md), phase 4 items 1–6): the remaining Kilo matrix runs (summary with the soft trigger on and off with carried summaries, hybrid without the soft trigger, hybrid per step), then choose the soft-trigger default and set F5 to Implemented. Blocked: the Kilo key is invalid (`provider_unauthorized` since 2026-11-26); needs a new key or another long-context provider.
- **F6 calibration** ([f6-trajectory-escalation.md](features/f6-trajectory-escalation.md), [benchmarks/escalation.md](benchmarks/escalation.md)): the calibration runs need a much weaker fast model than `gpt-6-luna`, which solved every task; for example a 2B free model on Kilo (same blocker).
- **F1 on real GitHub** ([f1-issue-to-pr.md](features/f1-issue-to-pr.md)): verified with fakes only; run issue to pull request end to end on the sandbox repository `vladimirbsuir/pointer-sandbox` and record the thesis metrics.
- **F7 gaps** ([benchmarks/desktop.md](benchmarks/desktop.md)): nemotron has no grid or image-cost calibration (Kilo key) and Mistral answered 402 until the Experiment plan is activated; optional for the thesis.

## Deployment

- The production VPS ([06-deployment.md](06-deployment.md)) has not been redeployed since before F1: migrations V18 to V41 and the features since then are not live. `POINTER_PUBLIC_URL` is needed for the F1 webhooks.
- The `gui` runner image needs 3.2 GB of disk unpacked and the current host has 1.9 GB free, so GUI sessions and the sandbox of local sessions stay off in production until the host grows.

## Roadmap

- **M7** ([05-roadmap.md](05-roadmap.md)): the remaining measurements and the thesis text.
