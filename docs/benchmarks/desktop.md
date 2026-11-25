# GUI testing sandbox

Measurements for [F7](../features/f7-gui-sandbox/README.md). Phase 1.1 (resources of the display stack) and the live check of phase 1.2 (providers, schema cost) are below; the benchmark of phase 1.6 (success, grounding, cost and masking per model) will be added here.

## Phase 1.1: resources of the `gui` image

Measured on 2026-11-25 with `infra/bench/desktop/measure.sh` on Docker Desktop 28.3 (WSL 2, 22 CPUs and 15.4 GB visible to Docker), container limits `--cpus 2 --memory 4g --shm-size 512m`. Memory is `memory.current` of the container's cgroup (it includes the page cache), processes are `pids.current`, which counts threads too, as Docker's `PidsLimit` does.

### Image size

| Target | Unpacked (`du -sx /`) | Compressed (registry) |
|---|---|---|
| `slim` | 246 MB | 86 MB |
| `full` | 1,996 MB | 703 MB |
| `gui` | 3,236 MB | 1,175 MB |

The `gui` target adds 1.24 GB unpacked over `full`, mostly Chromium, WebKitGTK with the Tauri build headers and the Java runtime.

### Display and applications

| State | Memory | Processes and threads |
|---|---|---|
| Runner container before `desktop_start` | 2 MB | 3 |
| Xvfb 1280x800, D-Bus and openbox | 26 MB | 7 |
| plus Chromium with one page (`form-web`) | 160 MB | 121 |
| plus a Tk window instead (`tk-counter`) | 47 MB | |

`desktop_start` reaches a display with a working window manager in 160 ms.

### Process limit

Chromium with one page next to a parallel `cargo build` of a crate with `tokio`, `serde` and `regex` peaked at 283 processes and threads (cgroup `pids.peak`), against the default limit of 512.

### Decisions taken from these numbers

- **The default image stays `full`.** `gui` is offered as `--runner gui` in `init-env.sh` and as the `gui` tag of the image. The desktop tools are not offered to the model before phase 1.2, and the extra 1.2 GB per host is not worth it on the shared production host until then; phase 1.2 counts the schema cost and revisits the default (below: it stays `full`).
- **No separate memory limit for GUI sessions.** An idle display costs 26 MB and a browser about 160 MB, well inside the 4 GB of the `full` runner. `pointer.container.gui-memory` is not added.
- **`pointer.container.pids-limit` stays 512.** The peak with a browser and a parallel build used 55 % of it. Electron is checked again with its fixture in phase 1.6.

## Phase 1.1: behaviour checked by the runner tests

The runner tests run inside the `gui` image (`POINTER_DESKTOP_TESTS=1`, see [tests-and-metrics.md](../features/f7-gui-sandbox/tests-and-metrics.md)). Findings that changed the design:

| Question | Finding | Consequence |
|---|---|---|
| Does `xdotool type` deliver non-ASCII text? | Without a UTF-8 locale it fails with "Invalid multi-byte sequence". With `LC_ALL=C.UTF-8` it typed Cyrillic, accented Latin and emoji exactly into Tk and into Chromium. | The runner calls the X tools with `C.UTF-8` and the image sets `LANG=C.UTF-8`. `desktop_type` uses keys by default; the clipboard path is kept only as an explicit `method: paste`. |
| When is openbox ready? | `_NET_SUPPORTING_WM_CHECK` appears before openbox handles map requests: a window opened right then stayed unmapped at 1x1 (reproduced in a plain shell, three displays in parallel). `_NET_CLIENT_LIST` appears only at the end of its start-up. | `desktop_start` waits for `_NET_CLIENT_LIST`. |
| Which WebKitGTK variables are needed under Xvfb? | `webkit-counter` (a window of `wry`, the webview layer of Tauri, on WebKitGTK 2.50 from Debian trixie) rendered and took clicks with all four combinations of `WEBKIT_DISABLE_DMABUF_RENDERER` and `WEBKIT_DISABLE_COMPOSITING_MODE`, including none. The first WebKit start in a fresh container takes several seconds longer. | Both variables stay in the image as a safeguard for other WebKitGTK versions; neither is required today. |
| Does an HTML5 drag need intermediate moves? | A press, a jump and a release left the `form-web` list unchanged; `desktop_drag` with ten 20 ms steps reordered it. | Confirms the stepped drag of the spec. |
| Does `xdotool key` reject unknown names? | `xdotool key foo` exits 0 and presses nothing useful. | `desktop_key` validates every name with `XStringToKeysym` first and fails with `invalid_key`. |

## Phase 1.2: live check with real models

Measured on 2026-11-25 with `infra/bench/desktop/live_check.py` against a local backend: the bench device ran inside the `gui` image as the session's executor, so every desktop operation ran on a real Xvfb display. The task: start the display, launch `tk-counter`, click Increment twice, type `hello` into the field and report the count; while the turn ran, a second user message ("Also tell me the title of the window") was sent, so the next request held a tool result, the screenshot message and the new user message in a row. A run passes when the application's own state file shows two increments and the typed text.

### Providers and message order

| Provider | Model | Task | Tool → screenshots → assistant | Tool → screenshots → user | Calls | Time | Prompt tokens (cached) |
|---|---|---|---|---|---|---|---|
| Experiential Labs | `gpt-6-luna` (pixels) | pass, 2 of 2 | accepted | accepted | 6 | 16–21 s | 35.8k (27.4k) |
| Experiential Labs | `gpt-6-luna` (0–1000 grid) | pass | accepted | accepted | 7 | 35 s | 42.1k (33.4k) |
| Experiential Labs | `deepseek-v4.1-flash` | pass, 2 of 2 | accepted | accepted | 10–11 | 25–31 s | 71.4k (66.9k) |
| TokenHarbor | `deepseek-v4.1-flash:free` | pass | accepted | accepted | 10 | 29 s | 78.7k (68.2k) |
| TokenHarbor | `mimo-v2.6-flash:free` | pass | accepted | accepted | 8 | 65 s | 63.0k (52.2k) |
| Kilo Gateway | `nvidia/nemotron-3-ultra-550b-a55b:free` | fail | rejected: "No endpoints found that support image input" | | 5 | | |
| VyceAI | `glm-5.3-flash`, `deepseek-v4.1` | not run | the provider answered "currently unavailable" and "requires lite tier or higher" | | | | |

Every provider that accepts images accepted both sequences, so the synthetic user message after the tool results needs no per-provider variant. The first `deepseek-v4.1-flash` run had finished the task when the free plan's limit of 10 requests per minute failed its last step; the second run, a minute later, passed. Kilo's free model is text only and is unsuitable for GUI work, as the spec expects for a model without vision; with its vision flag off the desktop tools are not offered to it at all.

Observations:

- **The coordinate conventions land on the same pixels.** With pixels `gpt-6-luna` clicked the button at (350, 408); with the grid it clicked (273, 511), which the backend mapped to the marker (349, 408) on the same 1280x800 screenshot.
- **Models batch inputs.** `gpt-6-luna` used `clicks: 2` for two increments and once `observe: false` for an intermediate click, so a task took 6–7 calls; the DeepSeek models clicked the button, then the field, and checked the state file with `run_command`.
- **One invalid call.** `mimo-v2.6-flash:free` once called `desktop_wait` with a plain wait above the 10-second limit and corrected itself after the `invalid_arguments` error.
- **Screenshots dominate the prompt.** With 3–5 screenshots per task and no masking, a task of 7–10 steps sent 36–79k prompt tokens, of which 76–94 % were served from the provider cache. Image masking (phase 1.3) targets the uncached part and the growth of the prefix.

### Schema cost

The prompt tokens of a one-step Agent turn ("Reply with the single word OK.") on `gpt-6-luna`, with the model's vision flag off (no desktop tools) and on (the eleven desktop tools added), on the same runner:

| Convention | Without desktop tools | With them | Difference |
|---|---|---|---|
| Pixels | 1,704 | 3,136 | +1,432 |
| 0–1000 grid | 1,704 | 3,322 | +1,618 |

The rows hold 7,619 characters of names, descriptions and schemas, plus the convention sentence on six tools; the grid sentence is longer, hence the difference between the variants. The desktop tools cost about as much as all other built-in Agent mode tools together (6,121 characters).

### Decisions taken from these numbers

- **The default image stays `full`.** The schemas are sent only to sessions whose runner reports `desktop`, so the default image decides nothing about their cost; what it decides is 1.24 GB more image per host, which the shared production host does not need. Operators who want the tools pick `--runner gui`, which also sets `POINTER_RUNNER_DESKTOP=true`.
- **Eleven separate tools stay for now.** About 1.4–1.6k tokens per request is a fixed prefix that providers cache (above), so it costs about a tenth of its uncached price after the first step; the single-tool form remains an option for the comparison in phase 1.6.
