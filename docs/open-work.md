# Open work

What is left after the roadmap and the approved features ([features/README.md](features/README.md)), as of 2026-11-26. Each item says where it is and what done looks like. Remove an item when it is done, or move it to the relevant spec.

## Gaps found in the live check of 2026-11-26

The realtime and executor fixes were checked in the Tauri app on Windows against a local backend, through a proxy that could drop events, stall the connection and inject close codes. They all worked: a dropped event re-subscribed from the last contiguous `seq`; a stalled link reconnected within a minute; a connection stayed up for more than 15 minutes with a token refresh and no `4401`; sign-out closed the socket at once; `4426` showed the update message and stopped reconnecting; `4403` signed the app out; `run_command` returned about a second after the shell exited although a background child still held the pipes; and an orphaned grandchild was killed on both timeout and cancel. The check found that the backend sent no keep-alive pings, so an idle app reconnected every minute; that is fixed. Left:

- **No device revocation in the backend.** [03-protocol.md](03-protocol.md) lists `GET /devices`, `PATCH /devices/{id}` and `DELETE /devices/{id}` and the close code `4403` for a disabled account or a revoked device, but there is no device controller and the gateway never closes with `4403` (the client side was checked with an injected close only). Done: add the endpoints, close the device's connection with `4403` when it is revoked or the account is disabled, and refuse its refresh token; or remove both from the protocol.
- **Undelivered results not checked live.** Results that finish while the app is disconnected are sent after `welcome` (efc1a65); the live check did not cut the connection during a running command. Check: stall the connection while a `run_command` runs and confirm the result reaches the turn after the reconnect.

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
