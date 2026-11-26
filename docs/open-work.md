# Open work

What is left after the roadmap and the approved features ([features/README.md](features/README.md)), as of 2026-11-26. Each item says where it is and what done looks like. Remove an item when it is done, or move it to the relevant spec.

## Questions for the owner

1. **Queued messages after a turn that did not complete.** `SessionWriter.finishTurn` ([SessionWriter.java:282](../backend/src/main/java/dev/pointer/session/SessionWriter.java)) starts the next queued turn only when the reason is `completed`. After `step_limit` or `budget_exceeded` the queued messages keep `turnId: null` and the session goes idle until the user sends another message, while [03-protocol.md](03-protocol.md) section 5.2 says a queued message "starts the next turn". Decide: start them after `step_limit` (and keep them waiting after `budget_exceeded`, `cancelled`, `failed`), or document the current behaviour in the protocol.
2. **Secret filter of `sandbox_sync`.** `is_secret` ([sync.rs:150](../desktop/src-tauri/crates/pointer-executor/src/sync.rs)) leaves out `.env`, `.env.*` except examples, key and certificate files and a few credential files. It does not catch `prod.env`, `.envrc`, `.aws/credentials`, `credentials.json`, `secrets.yaml`. Decide whether to widen the list (and how far), or keep it and say in the tool description that the user's `.gitignore` is the real boundary.

## Bugs found in the code review of 2026-11-26

Found by a review of the hot spots (agent loop, containers and execution, Rust executor, client realtime) and checked against the code. Sixteen findings were fixed in 646f40e, efc1a65 and d9412a8; these are the ones left.

### Rust executor (`desktop/src-tauri/crates/pointer-executor/src/`)

1. **`read_file` reads the whole file before applying its limit.** `read_text` ([files.rs:281](../desktop/src-tauri/crates/pointer-executor/src/files.rs)) calls `fs::read` without a size check; the 256 KB cap of `read_file` applies only to the selected lines afterwards. `write_file` and `edit_file` also read the whole old content. A multi-gigabyte log or dataset in the project can exhaust memory, and the release profile aborts on panic, so the desktop app dies. Done: refuse files over a size limit (or stream lines up to `offset + limit`) for `read_file`, and refuse to edit files over a limit, with a clear error code; a test with a large file.
2. **A background child keeps `run_command` waiting until the timeout.** `Finish::Exited` ([command.rs:206](../desktop/src-tauri/crates/pointer-executor/src/command.rs)) needs both the exit status and closed output pipes, so `server &` or any child that inherits the handles makes the call wait the full `timeoutMs` and report `timeout`. On Windows `taskkill /T` in `kill_tree` ([command.rs:262](../desktop/src-tauri/crates/pointer-executor/src/command.rs)) does not reach a child whose parent already exited, so the orphan keeps running. Done: return once the shell exits plus a short grace for the pipes; put the process tree in a Windows Job Object (kill on close) and a process group on Unix, so cancel and timeout kill everything; tests for both platforms.
3. **A file that shrinks during packing corrupts the sync archive.** `append` ([sync.rs:230](../desktop/src-tauri/crates/pointer-executor/src/sync.rs)) writes the header size from `metadata.len()`, and `tar::Builder::append` does not check that the copied length matches. A log truncated during a snapshot shifts every later entry; the sandbox then fails to extract or reads content as headers (containment still holds). Done: copy into a buffer or temp file of the declared size, padding or failing the snapshot when the length changed; a test that truncates a file while packing.
4. **A reused or missing `callId` cancels the wrong calls.** `running.insert(call_id, ...)` ([lib.rs:160](../desktop/src-tauri/crates/pointer-executor/src/lib.rs)) drops an earlier sender with the same id, which its receiver treats as a cancel, and a missing `callId` defaults to `""`, so such calls share one entry. Low risk because the backend generates UUIDv7 ids. Done: reject a request without `callId` and refuse a duplicate id while the first is running.

### Backend

5. **Deleting a session during a container start can orphan the container.** `onSessionDeleted` ([RemoteSessionService.java:408](../backend/src/main/java/dev/pointer/container/RemoteSessionService.java)) removes the container right after the commit, while a start thread inside `runtime.create`/`runtime.start` can create or start it afterwards; the row is gone, so nothing stops it. Done: after `create`/`start`, check that the workspace row still exists and remove the container if not; or remove containers whose `dev.pointer.session` label has no session in the idle check.
6. **A cancel can be lost right before a turn registers.** `running.put` happens inside the turn's thread ([AgentRunner.java:189](../backend/src/main/java/dev/pointer/agent/AgentRunner.java)), and a chained turn registers only after the previous one is removed, so a cancel in that window hits no entry and the new turn runs to the end. Narrow window. Done: register the turn before launching it (in `send`/`finishTurn`'s caller), or keep a per-session cancel flag that the next turn reads.
7. **An approval left pending after an exception can flip an idle session to running.** `ApprovalService.resolve` ([ApprovalService.java:95](../backend/src/main/java/dev/pointer/session/ApprovalService.java)) sets the session to `running` without checking that a turn waits for the approval. It needs an exception between `approvals.request` and `await`, or an interrupt, which leaves the approval pending after the turn ended. Done: `resolve` sets `running` only when a waiter exists, and the interrupted branch of `await` cancels the approval in the database.
8. **Viewer input to the display is not bounded.** `DesktopViewer.forward` ([DesktopViewer.java:80](../backend/src/main/java/dev/pointer/container/DesktopViewer.java)) chains sends to the display without a limit, so a fast viewer against a slow display grows memory, and after one failed send all later input is dropped silently. Input is ignored by the view-only `x11vnc`, so this matters only for memory. Done: drop or close above a small queue, like the display-to-viewer direction.
9. **Results are not checked against the sending device.** `onResult`/`onOutput` ([ExecutionService.java:133](../backend/src/main/java/dev/pointer/execution/ExecutionService.java)) accept a result for any `callId` from any device. Not exploitable in practice (UUIDv7 ids sent only to the target device); hardening only. Done: the gateway passes the device id and the call is completed only when it matches.

### Client

10. **No detection of `seq` gaps.** Both the core ([realtime/mod.rs](../desktop/src-tauri/src/realtime/mod.rs), `track_event`) and the frontend ([use-conversation.ts:69](../desktop/src/features/sessions/use-conversation.ts)) drop `seq <= last` but accept jumps, while protocol section 4.4 asks to re-subscribe from the last contiguous `seq`. Low impact today because the backend closes an overflowing connection instead of dropping messages. Done: on a gap, re-subscribe with the last contiguous `seq`.
11. **4403 only stops reconnecting.** After the review fix the core stops on `4403` (account disabled or device revoked) and `4426` (unsupported version), but the protocol asks to sign out on `4403` and to ask the user to update on `4426`; the frontend shows only "disconnected". Done: emit the close reason with the status event and handle it in the frontend.
12. **Unverified: live output may be dropped on item replacement.** `upsert` in [use-conversation.ts](../desktop/src/features/sessions/use-conversation.ts) replaces a tool call on `item.updated` or a snapshot and may drop its accumulated `liveOutput`. Check whether snapshots carry tool calls; fix by keeping `liveOutput` when the replacement has no `output`.

### Not verified live

- The realtime fixes of efc1a65 (read timeout, token refresh every 30 seconds, abort of the writer and refresher on sign-out, flush of undelivered results after `welcome`, final close codes) compile and pass clippy but were not run in the Tauri app. Check: sleep the machine or cut the network with the app connected and confirm it reconnects within about a minute; stay connected for more than 15 minutes and confirm no `4401` close; sign out and confirm the device goes offline on the backend at once.

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
