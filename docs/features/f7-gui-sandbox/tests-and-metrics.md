# F7. Tests and thesis metrics

Part of [F7](README.md). Covers GUI-10 and the tests of every phase.

## Fixture applications

Small applications with known behavior, built and kept in `infra/bench/desktop/apps/`, used by both the tests and the benchmark. Each exposes a check that does not depend on a screenshot (a file it writes, a log line, a DOM state read through the browser's remote debugging port, or an HTTP endpoint).

| App | Toolkit | Purpose |
|---|---|---|
| `form-web` | Static HTML and JavaScript in Chromium | A form with validation, a list that updates and can be reordered by drag and drop, a dialog; the web case. |
| `tk-counter` | Python Tk | A button, a label and a text entry; the smallest native window, used by the runner tests. Tk has no accessibility tree, so it is also the pixels-only case. |
| `gtk-notes` | GTK 3 | A notes list, a menu, a file chooser; native widgets and an accessibility tree. |
| `qt-settings` | Qt (PySide 6 from `pip`) | Tabs, checkboxes, a spin box; a second native toolkit. |
| `electron-todo` | Electron | The Electron case, with Chromium accessibility. |
| `tauri-counter` | Tauri on Linux | The case closest to Pointer itself; WebKitGTK. |
| `swing-calc` | Java Swing | The Java case; accessibility through the Java ATK wrapper, expected to be weak. |

Tasks per app come in three kinds: a single action (click a button, check the label), a short sequence (fill a form and submit), and a task with a trap (a modal dialog appears, a field validates and rejects the first input).

## Tests by phase

### 1.1 Privileges, repository proxy and runner

Backend tests, in the usual Docker-based test setup:

- Repository proxy: clone and fetch through the proxy reach a test git server standing in for GitHub with the stored token added; a push to the session branch passes; a push to another branch, a tag, or a deletion of the session branch is refused with `403` and nothing reaches the server; a gzip-encoded body is checked the same way; another runner's token, a user token or a repository of another project gets `404`; a large pack is streamed without growing the heap.
- Repository operations: `pointer.repo_prepare` and `pointer.repo_publish` carry no token; the clone's `origin` is the proxy URL.
- Container spec: the created container runs as root with exactly the listed capabilities and `no-new-privileges`; a container whose spec hash label differs is recreated on the same volume on start, and the checkout is intact afterwards.

Runner tests inside the `gui` image, gated by an environment variable set only in the image, so a plain `cargo test` on a developer machine skips them:

- Privileges: `apt-get install` of a small package succeeds; `runuser -u pointer --` runs a command as `pointer`; `sudo true` succeeds; `git status` works in a checkout owned by uid 1000; no environment of any process contains a GitHub token during a publish.
- Display lifecycle: `desktop_start` is idempotent, returns the current state when called without dimensions on a running display, rejects a second call with other dimensions, bounds the size, fails `desktop_*` before start with `display_not_started`; after start a `run_command` child sees `DBUS_SESSION_BUS_ADDRESS`.
- Launch: a process that stays alive returns its pid and log path, one that exits within the grace period returns `exited` with its code and log tail, `desktop_stop` ends the process group, the limit of live processes holds.
- Input and capture on `tk-counter`: click the button at known coordinates, the screenshot differs from the previous one and the app's check file changes; type ASCII text and Cyrillic, accented and emoji text into the entry and compare what arrived, which decides whether the paste fallback is needed; a key combination; drag; scroll. A screenshot has the expected image dimensions and a valid PNG header; a zoomed capture has the zoom's.
- Scaling: on a 1920x1200 display with the default image limit, captures are 1280x800 and report the screen size.
- Drag on `form-web`: reordering a list item by `desktop_drag` changes the DOM order, which a jump without intermediate moves does not.
- Wait: `untilWindow` returns `met` when a window with the title appears and `met: false` after the timeout; `untilLog` sees a line written by a launched process.
- Rendering: `tauri-counter`, `electron-todo` and `chromium` show a window whose screenshot is not uniform, with the image's WebKit and sandbox variables; the test also runs `tauri-counter` without the WebKit variables to record which of them is needed.
- Failure cases: coordinates out of range, an unknown key name (with a check that `xdotool` alone would have accepted it), text over the limit.
- Capability: the runner reports `desktop` when the binaries exist and does not when they are hidden from the path.

Measurements recorded with the tests: image size, memory of an idle display and of one with Chromium, and the peak process and thread count of Chromium and of `electron-todo` next to a parallel `cargo build`, which sets `pointer.container.pids-limit`.

As implemented in phase 1.1: the runner tests are in `desktop/src-tauri/crates/pointer-executor/src/desktop/tests.rs` and run with `POINTER_DESKTOP_TESTS=1` inside the `gui` image (the command is in `infra/bench/desktop/README.md`), each test on its own display so they run in parallel. The fixtures `tk-counter`, `form-web` and `webkit-counter` (a `wry` window standing in for `tauri-counter`) are in `infra/bench/desktop/apps/`; `electron-todo`, `tauri-counter`, `gtk-notes`, `qt-settings` and `swing-calc` come with phase 1.5 and 1.6, so the Electron process peak is measured then. The measurements come from `infra/bench/desktop/measure.sh` and are in [desktop.md](../../benchmarks/desktop.md). The backend tests are `RepositoryProxyTests` (real `git` against `git http-backend` through the proxy), `ReceivePackCommandsTests`, `DockerContainerRuntimeTests` (a real container through Docker) and `RemoteExecutionTests`.

### 1.2 Backend and agent

- `ToolRegistry`: desktop tools appear for a device with `desktop` and not for one without; for an offline runner the stored capabilities decide; a runner that never connected follows `pointer.container.desktop`.
- `GatewayHandler`: a runner's `desktop` capability is kept and stored, `viewer` and `relay` are still dropped.
- Vision gating: no desktop tools when the step model lacks vision; with a `fast` tier without vision the tool list is unchanged, a desktop call there fails with `needs_vision`, is not sent to the executor, and the next step runs on the step model with a `model_escalated` notice whose reason is `vision`; `StruggleSignals` ignores the call. A turn whose active context holds unmasked screenshots starts on the step model.
- `DesktopCoordinates`: identity at the defaults; the 0–1000 grid maps to pixels and back, with 1000 on the last pixel; a 1920x1200 display maps through the scaled image; out-of-range values fail before reaching the executor; result coordinates and tree bounds come back in model space; `marks` are in image pixels.
- `DesktopImages`: result images become attachments with `origin` `desktop`, dimensions are set, the item holds references and no base64, oversized and malformed images are rejected cleanly.
- `ContextAssembler`: a step with two screenshots renders the tool messages followed by one user message with two data URLs in call order; the same items render identically on every build; a model without vision gets the placeholder text; the data URL cache returns the same URL as a database read.
- A scripted fake LLM and a fake executor drive a turn through start, launch, click and screenshot and check the requests the model receives, once per coordinate convention.
- Protocol: `exec.result` with `images` round-trips through the gateway at the largest allowed size.
- Providers: a live check against every test provider sends the sequences tool, user, assistant and tool, user, user with an image and records whether each is accepted.
- Schema cost: the token count of the desktop tool schemas, per convention, is recorded for the open question on one tool or twelve.

As implemented in phase 1.2: `DesktopCoordinatesTests` (identity, grid, scaled display, out-of-range values, zoom rectangles, description variants), `DesktopContextTests` (one screenshot message per step in call order, identical renders and append-only growth, placeholders without vision) and `DesktopToolTests`, which drives whole turns with the fake LLM and a fake desktop executor: a pixel model on the default display with the largest allowed image through the gateway, a grid model on a 1920x1200 display with an out-of-range call that never reaches the executor, the tool list without vision or without a desktop device, a blind `fast` tier that escalates with `vision`, and oversized and malformed screenshots. `RemoteExecutionTests` checks that a runner's `desktop` survives its disconnection and that `viewer` and `relay` are dropped. The provider check and the schema cost are the live check in [desktop.md](../../benchmarks/desktop.md) (`infra/bench/desktop/live_check.py`).

### 1.3 Masking

- Boundary arithmetic of the image counter: keep, block alignment, `block = 1` equal to per-step masking, one screenshot per step and several per step.
- The property test of the append-only invariant over generated conversations with images and several policies, with the one allowed difference at a newly masked image.
- Masking applies under all three context strategies; originals remain; compaction input and cache-safe compaction render as specified in [agent-and-context.md](agent-and-context.md).
- Token estimate: an image of known size counts from its dimensions, one of unknown size counts the old constant.

### 1.4 Live view

- In the image: after `desktop_start`, `x11vnc` and `websockify` are listening and the stream shows the display (a test RFB client reads the framebuffer and sees the fixture window); before `desktop_start` nothing listens; the password file has the expected structure and both passwords are 8 characters.
- View-only: a test RFB client authenticated with the view password sends pointer and key events and the display does not change; a wrong password is refused.
- Tickets: single use, expire after 60 seconds, bound to the user and the session, refused for another user's session; the viewer limit returns `429` at issue and at open, and never closes an open stream.
- The relay forwards the handshake and full framebuffer updates larger than the servlet container's default message size both ways, negotiates the `binary` subprotocol, closes when the container stops or the session is deleted, and disconnects a viewer that does not read without affecting a second viewer.
- A closed, failing or slow stream never delays or fails a tool call.
- Reachability: the `published` and `network` modes both reach the container (the first in the local test setup, the second in the compose setup).
- Outdated containers: a workspace without a view secret reports `available: false`, and its container gets one when it is recreated.
- Authorization: another user cannot watch the session.
- Client: the panel asks for a ticket on open and disconnects on close, shows the empty state and the last-screenshot state, retries with a new ticket after a drop, handles a too-slow close, and sets `viewOnly`.

### 1.5 Accessibility tree

- The tree of `gtk-notes`, `qt-settings`, `electron-todo` and `form-web` contains the known controls with roles, names and bounds; clicking by `elementId` lands inside the element; a stale id fails with `element_not_found`.
- `swing-calc` with the ATK wrapper enabled produces a tree, possibly partial; `tk-counter` produces an empty one, and the tool says so instead of failing.
- `maxNodes` and `maxDepth` bound the output; the output order is stable.

### 1.6 Benchmark harness

The fixtures have reference solutions that pass the checks, as in the escalation harness (`--verify-fixture`), so a task that cannot be solved is found before any model is run.

## Benchmark

Harness in `infra/bench/desktop/` in the style of `infra/bench/escalation/`: it starts a server session on the bench account, gives the agent one task over one fixture application, lets it run, then runs the app's own check and stores the outcome, the steps, the screenshots, the usage rows and the timing. Results and analysis go to `docs/benchmarks/desktop.md`.

## Thesis metrics

1. **Success rate by model and task kind.** Check passes, over three repetitions, for each vision-capable test model, split by single action, sequence and trap tasks. The claim is which models are usable for GUI testing and where they fail.
2. **Click accuracy (grounding).** A synthetic task set where the agent must click a named target on a generated screen with many controls: the share of clicks inside the target's bounds, per model, per coordinate convention (`pixels` and `normalized`, which fixes each model's setting) and per display size (1024x768, 1280x800, and 1920x1080, which exceeds the image limit and is scaled). This isolates grounding from planning and shows what a wrong convention or provider-side resizing costs.
3. **Cost and time per task.** Steps, tokens (input, cached, output), cost, wall-clock time, split into model time, settle time, wait time and tool time. Seconds per step is the figure that decides whether the feature is pleasant to use.
4. **Share of screenshots in the prompt.** Input tokens attributable to images over the total, with and without masking, and the fixed share of the desktop tool schemas.
5. **Masking ablation.** `keep-images` of 1, 3 and all, with block sizes 1 and 4: success, cost, prompt prefix reuse from the usage ledger, and the share of repeated actions, which signals that the model lost something it needed. The claim is the cost saved at what success rate.
6. **Tree ablation (phase 1.5).** Pixels only against pixels plus the tree, per toolkit: success, steps, and the share of clicks done by `elementId`. The claim is where the tree pays off and where its coverage makes it useless.
7. **Isolation levels.** A comparison table of the approaches in the [README](README.md) (container display, background control through accessibility APIs on macOS, foreground control on Windows) on interference with the user, reproducibility, coverage of applications and platform fidelity. Based on the measurements for the container and on published descriptions for the others; the table says which parts are measured here.
8. **Resource cost.** Image size added, time from `desktop_start` to a usable display, memory of an idle display and of one with a browser, peak process count, and how many GUI sessions fit on the production host.
9. **Live view cost.** CPU and memory of `x11vnc` and `websockify` with no viewer and with one, CPU and heap of the backend relay per viewer, and the bandwidth of the stream while the agent works, to show the view is cheap enough to leave on.
10. **What a pass proves.** For a Tauri app, the same task run in the Linux container and, where possible, against the Windows build, to show how often a Linux pass transfers; limited to a few tasks and reported as such.
