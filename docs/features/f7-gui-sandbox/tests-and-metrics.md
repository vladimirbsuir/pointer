# F7. Tests and thesis metrics

Part of [F7](README.md). Covers GUI-10 and the tests of every phase.

## Fixture applications

Small applications with known behavior, built and kept in `infra/bench/desktop/apps/`, used by both the tests and the benchmark. Each exposes a check that does not depend on a screenshot (a file it writes, a log line, a DOM state read through the browser's remote debugging port, or an HTTP endpoint).

| App | Toolkit | Purpose |
|---|---|---|
| `form-web` | Static HTML and JavaScript in Chromium | A form with validation, a list that updates, a dialog; the web case. |
| `tk-counter` | Python Tk | A button and a label; the smallest native window, used by the runner tests. Tk has no accessibility tree, so it is also the pixels-only case. |
| `gtk-notes` | GTK 3 | A notes list, a menu, a file chooser; native widgets and an accessibility tree. |
| `qt-settings` | Qt (PyQt or PySide) | Tabs, checkboxes, a spin box; a second native toolkit. |
| `electron-todo` | Electron | The Electron case, with Chromium accessibility. |
| `tauri-counter` | Tauri on Linux | The case closest to Pointer itself; WebKitGTK. |
| `swing-calc` | Java Swing | The Java case; accessibility through the Java access bridge, expected to be weak. |

Tasks per app come in three kinds: a single action (click a button, check the label), a short sequence (fill a form and submit), and a task with a trap (a modal dialog appears, a field validates and rejects the first input).

## Tests by phase

### 1.1 Runner (inside the `gui` image)

Gated by an environment variable set only in the image, so a plain `cargo test` on a developer machine skips them.

- Display lifecycle: `desktop_start` is idempotent, rejects a second call with other dimensions, bounds the size, fails `desktop_*` before start with `display_not_started`.
- Launch: a process that stays alive returns its pid and log path, one that exits within the grace period returns `exited` with its code and log tail, `desktop_stop` ends the process group, the limit of live processes holds.
- Input and capture on `tk-counter`: click the button at known coordinates, the screenshot differs from the previous one and the app's check file changes; type text into the entry; a key combination; drag; scroll. A screenshot has the display's dimensions and a valid PNG header; a region capture has the region's.
- Failure cases: coordinates out of range, an unknown key name, text over the limit.
- Capability: the runner reports `desktop` when the binaries exist and does not when they are hidden from the path.

### 1.2 Backend and agent

- `ToolRegistry`: desktop tools appear for a device with `desktop` and not for one without.
- Vision gating: the tool list of a step contains them only when the step model has vision.
- `DesktopImages`: result images become attachments with `origin` `desktop`, dimensions are set, the item holds references and no base64, oversized and malformed images are rejected cleanly.
- `ContextAssembler`: a step with two screenshots renders the tool messages followed by one user message with two data URLs in call order; the same items render identically on every build; a model without vision gets the placeholder text.
- A scripted fake LLM and a fake executor drive a turn through start, launch, click and screenshot and check the requests the model receives.
- Protocol: `exec.result` with `images` round-trips through the gateway at the largest allowed size.

### 1.3 Masking

- Boundary arithmetic of the image counter: keep, block alignment, `block = 1` equal to per-step masking, one screenshot per step and several per step.
- The property test of the append-only invariant over generated conversations with images and several policies, with the one allowed difference at a newly masked image.
- Masking applies under all three context strategies; originals remain; compaction input and cache-safe compaction render as specified in [agent-and-context.md](agent-and-context.md).

### 1.4 Live view

- Watcher counting: the first watcher starts the stream, the last one stops it, a disconnect stops it, two watchers share one stream.
- In the image: after `desktop_start`, `x11vnc` and `websockify` are listening and the stream shows the display (a test RFB client reads the framebuffer and sees the fixture window); before `desktop_start` nothing listens; the password file has the expected structure.
- View-only: a test RFB client authenticated with the view password sends pointer and key events and the display does not change; a wrong password is refused.
- Tickets: single use, expire after 60 seconds, bound to the user and the session, refused for another user's session; the viewer limit returns `429`.
- The relay forwards the handshake and frames both ways, closes when the container stops or the session is deleted, and a closed or failing stream never delays or fails a tool call.
- Reachability: the `published` and `network` modes both reach the container (the first in the local test setup, the second in the compose setup).
- A failing or slow stream does not delay or fail a tool call.
- Authorization: another user cannot watch the session.
- Client: the panel asks for a ticket on open and disconnects on close, shows the empty state and the last-screenshot state, retries with a new ticket after a drop, and sets `viewOnly`.

### 1.5 Accessibility tree

- The tree of `gtk-notes`, `qt-settings` and `form-web` contains the known controls with roles, names and bounds; clicking by `elementId` lands inside the element; a stale id fails with `element_not_found`.
- `tk-counter` and `swing-calc` produce an empty or partial tree, and the tool says so instead of failing.
- `maxNodes` and `maxDepth` bound the output; the output order is stable.

### 1.6 Benchmark harness

The fixtures have reference solutions that pass the checks, as in the escalation harness (`--verify-fixture`), so a task that cannot be solved is found before any model is run.

## Benchmark

Harness in `infra/bench/desktop/` in the style of `infra/bench/escalation/`: it starts a server session on the bench account, gives the agent one task over one fixture application, lets it run, then runs the app's own check and stores the outcome, the steps, the screenshots, the usage rows and the timing. Results and analysis go to `docs/benchmarks/desktop.md`.

## Thesis metrics

1. **Success rate by model and task kind.** Check passes, over three repetitions, for each vision-capable test model, split by single action, sequence and trap tasks. The claim is which models are usable for GUI testing and where they fail.
2. **Click accuracy (grounding).** A synthetic task set where the agent must click a named target on a generated screen with many controls: the share of clicks inside the target's bounds, per model and per display size (1024x768, 1280x800, 1920x1080). This isolates grounding from planning.
3. **Cost and time per task.** Steps, tokens (input, cached, output), cost, wall-clock time, split into model time, settle time and tool time. Seconds per step is the figure that decides whether the feature is pleasant to use.
4. **Share of screenshots in the prompt.** Input tokens attributable to images over the total, with and without masking.
5. **Masking ablation.** `keep-images` of 1, 3 and all, with block sizes 1 and 4: success, cost, prompt prefix reuse from the usage ledger, and the share of repeated actions, which signals that the model lost something it needed. The claim is the cost saved at what success rate.
6. **Tree ablation (phase 1.5).** Pixels only against pixels plus the tree, per toolkit: success, steps, and the share of clicks done by `elementId`. The claim is where the tree pays off and where its coverage makes it useless.
7. **Isolation levels.** A comparison table of the approaches in the [README](README.md) (container display, background control through accessibility APIs on macOS, foreground control on Windows) on interference with the user, reproducibility, coverage of applications and platform fidelity. Based on the measurements for the container and on published descriptions for the others; the table says which parts are measured here.
8. **Resource cost.** Image size added, time from `desktop_start` to a usable display, memory of an idle display and of one with a browser, and how many GUI sessions fit on the production host.
9. **Live view cost.** CPU and memory of `x11vnc` and `websockify` with no viewer and with one, and the bandwidth of the stream while the agent works, to show the view is cheap enough to leave on.
10. **What a pass proves.** For a Tauri app, the same task run in the Linux container and, where possible, against the Windows build, to show how often a Linux pass transfers; limited to a few tasks and reported as such.
