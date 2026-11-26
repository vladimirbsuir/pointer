# Open work

What is left after the roadmap and the approved features ([features/README.md](features/README.md)), as of 2026-11-26. Each item says where it is and what done looks like. Remove an item when it is done, or move it to the relevant spec.

## Not verified live

- The realtime fixes of efc1a65 (read timeout, token refresh every 30 seconds, abort of the writer and refresher on sign-out, flush of undelivered results after `welcome`, final close codes) compile and pass clippy but were not run in the Tauri app. Check: sleep the machine or cut the network with the app connected and confirm it reconnects within about a minute; stay connected for more than 15 minutes and confirm no `4401` close; sign out and confirm the device goes offline on the backend at once.
- The fixes of the open-work pass of 2026-11-26 (the review leftovers) are covered by unit and integration tests but were not run in the Tauri app: check that a gap in `seq` re-subscribes (for example by dropping a message in the dev mock), that revoking the device on the backend closes with `4403` and signs the app out, that a `4426` close shows the update message, and that `run_command` with `server &` returns at once and the server dies with the call's cancel or timeout on Windows.

## Measurements and runs still open

- **F5 phase 4** ([f5-cache-aware-context.md](features/f5-cache-aware-context.md), phase 4 items 1–6): the remaining Kilo matrix runs (summary with the soft trigger on and off with carried summaries, hybrid without the soft trigger, hybrid per step), then choose the soft-trigger default and set F5 to Implemented. Blocked: the Kilo key is invalid (`provider_unauthorized` since 2026-11-26); needs a new key or another long-context provider.
- **F6 calibration** ([f6-trajectory-escalation.md](features/f6-trajectory-escalation.md), [benchmarks/escalation.md](benchmarks/escalation.md)): the calibration runs need a much weaker fast model than `gpt-6-luna`, which solved every task; for example a 2B free model on Kilo (same blocker).
- **F1 on real GitHub** ([f1-issue-to-pr.md](features/f1-issue-to-pr.md)): verified with fakes only; run issue to pull request end to end on the sandbox repository `vladimirbsuir/pointer-sandbox` and record the thesis metrics.
- **F7 gaps** ([benchmarks/desktop.md](benchmarks/desktop.md)): nemotron has no grid or image-cost calibration (Kilo key) and Mistral answered 402 until the Experiment plan is activated; optional for the thesis.

## Deployment

- The production VPS ([06-deployment.md](06-deployment.md)) has not been redeployed since before F1: migrations V18 to V40 and the features since then are not live. `POINTER_PUBLIC_URL` is needed for the F1 webhooks.
- The `gui` runner image needs 3.2 GB of disk unpacked and the current host has 1.9 GB free, so GUI sessions and the sandbox of local sessions stay off in production until the host grows.

## Roadmap

- **M7** ([05-roadmap.md](05-roadmap.md)): the remaining measurements and the thesis text.
