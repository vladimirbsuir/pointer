# GUI testing sandbox

Measurements for [F7](../features/f7-gui-sandbox/README.md). Phase 1.1 (resources of the display stack), the live check of phase 1.2 (providers, schema cost), image masking (1.3), the live view (1.4), the accessibility tree (1.5) and the benchmark of phase 1.6 (success, grounding, cost, the masking and tree ablations, calibration and the defaults taken from them) are below.

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

## Phase 1.3: image masking

Measured on 2026-11-25 with `infra/bench/desktop/live_check.py --task clicks` against a local backend, the bench device inside the `gui` image as the executor and TokenHarbor `deepseek-v4.1-flash:free` as the model (Experiential Labs' free plan allows 10 requests per minute, fewer than the task needs). The task: start the display, launch `tk-counter`, click Increment six times with one call per click and look at the count after each, type `hello` and report the count. It produces 9–11 screenshots in 13–15 requests. The backend was restarted with each screenshot policy (`POINTER_DESKTOP_KEEP_IMAGES`, `POINTER_DESKTOP_IMAGE_MASK_BLOCK`); the result masking of F5 was off (`summary` strategy) in all runs, and every run passed.

| Screenshots kept | Run | Requests | Screenshots | Prompt tokens | Cached | Uncached | Last request | Time |
|---|---|---|---|---|---|---|---|---|
| all | 1 | 14 | 9 | 133.0k | 123.5k | 9.5k | 13.2k | 96 s |
| all | 2 | 14 | 9 | 132.2k | 123.3k | 9.0k | 13.2k | 100 s |
| keep 3, block 4 (default) | 1 | 13 | 9 | 108.9k | 92.3k | 16.7k | 10.5k | 88 s |
| keep 3, block 4 (default) | 2 | 14 | 9 | 119.4k | 108.5k | 10.8k | 10.6k | 86 s |
| keep 1, block 1 | 1 | 14 | 9 | 98.1k | 86.5k | 11.6k | 8.1k | 71 s |
| keep 1, block 1 | 2 | 14 | 11 | 98.7k | 86.3k | 12.4k | 8.2k | 89 s |

Observations:

- **Masking shrinks the prompt by 10–26 %** on a task of nine screenshots: the default policy sent 109–119k prompt tokens instead of 132–133k, `keep 1, block 1` 98k. The saving grows with the length of the task, since without masking every screenshot stays in every later request.
- **The default policy moved its boundary once.** At the seventh screenshot four screenshots were masked, and the request fell from 10.7k to 8.9k tokens; that request was the only one with a large uncached part (2.7k against about 0.8k at other steps); the first of the two runs also started on a cold cache (4.9k), hence its larger uncached total.
- **With a cache discount the saving is small on a task this short.** At a cached price of a tenth, the runs cost the equivalent of 21–22k uncached tokens without masking, 22–26k with the default policy and 20–21k with `keep 1, block 1`. Most of what masking removes was served from the cache anyway; the gain is the smaller context, which matters for long tasks, small context windows and providers without a cache.
- **Per-step masking is cheap for screenshots with `keep 1`.** The newly masked screenshot is always the one of the previous step, so each move invalidates only the last step's messages; this differs from text results, where the masked result lies `keep` results back. Phase 1.6 compares the policies on longer tasks and counts repeated actions, which would show the model missing an older screenshot; in these runs every configuration clicked seven times (six increments and the text field), and one run with `keep 1` took two extra screenshots before its answer, the only extra observation seen.
- **The image estimate is about twice the provider's count.** Each step with a screenshot added about 760 reported prompt tokens, including its call, result and line, while the estimate counts 1,366 for the 1280x800 image alone; this provider counts roughly 1,400 pixels per token. The estimate errs on the safe side for compaction thresholds; `pixels-per-token` stays 750 until phase 1.6 calibrates it against all test providers.

### Storage of screenshots

The 94 screenshots stored by the live checks of phases 1.2 and 1.3 are 1280x800 PNG files of 26.7–30.7 KB, 29.7 KB on average; a screen with a browser page or a photo holds more detail and is limited to 2 MB per image (`pointer.desktop.max-image-bytes`). A GUI session of a hundred steps therefore stores about 3 MB of screenshots of a simple application. Screenshots are removed with their session and no count or age limit is added in stage 1; the question is reopened if the benchmark of phase 1.6 finds sessions with much larger screens.

## Phase 1.4: live view

Measured on Docker Desktop (Windows host, `published` access) with a server session in the `gui` image, the backend under `bootRun` and the desktop frontend in the browser preview, which counted the bytes of the stream WebSocket. CPU is the share of one core over 15 seconds, read from `/proc` in the container and from the Java process on the host.

| Scene | Viewer | `x11vnc` CPU | `websockify` CPU | Xvfb CPU | Backend CPU | Stream |
|---|---|---|---|---|---|---|
| xeyes, idle pointer | none | 0.1 % | 0 % | 0.3 % | | |
| xeyes, idle pointer | one | 0.2–0.6 % | 0 % | 0.3 % | | |
| xeyes, pointer moved 10 times a second | one | 0.7 % | 0 % | 0.9 % | | 0.2 KB/s |
| Agent turn on `python3 -m tkinter` and xeyes (14 tool calls including an `apt-get install`) | one | | | | | 21 KB in the first 62 s of the stream, at most 1 KB/s |
| Full-screen grid of random colours, 5 frames a second | none | 0.1 % | 0 % | 10.6 % | 0.3 % | |
| Full-screen grid of random colours, 5 frames a second | one | 6.3 % | 0.9 % | 13.7 % | 4.6 % | 567 KB/s |

Memory: `x11vnc` 17–21 MB, `websockify` 36 MB plus 28 MB for the process it forks per viewer, Xvfb 69–79 MB. The first frame of a new viewer was 7 KB for a mostly black screen.

Observations:

- **The view is cheap enough to leave on.** Without a viewer `x11vnc` does not poll the screen; with one, the stream of an ordinary GUI task stays near 1 KB/s because noVNC negotiates compressed encodings and only changed regions are sent.
- **The worst case is bounded by the content, not by the relay.** Random colours defeat compression; at 567 KB/s the backend spent about 5 % of a core relaying. The per-viewer queue limit of 8 MB holds about 14 seconds of this stream before the viewer is dropped as too slow.
- **The runner's display restarts nothing for the view.** The password file, the two processes and the published port add no measurable start-up time to `desktop_start`; the image is unchanged, as `x11vnc` and `websockify` were installed in phase 1.1.

## Phase 1.5: accessibility tree

### Coverage per toolkit

Measured on 2026-11-25 by the runner tests in `desktop/src-tauri/crates/pointer-executor/src/desktop/tests/tree.rs`, run inside the `gui` image with `POINTER_DESKTOP_TREE_REPORT` set (commands in `infra/bench/desktop/README.md`). Each fixture lists the controls a user would act on; a control counts as found when the focused tree has an element with its role and name. The time is one `desktop_tree` call with the default limits, run serially, including the start of the reader process.

| Fixture | Toolkit (as AT-SPI reports it) | Controls found | Elements listed | Time | Click by id |
|---|---|---|---|---|---|
| `gtk-notes` | GTK 3 (`gtk`) | 6 of 6 | 7 | 81 ms | works |
| `qt-settings` | Qt 6 through PySide 6 (`Qt`) | 5 of 5 | 8 | 122 ms | works |
| `form-web` | Chromium 156 through `pointer-chromium` (`Chromium`) | 3 of 3 | 12 | 183 ms | works |
| `electron-todo` | Electron 39 with `--force-renderer-accessibility` (`Chromium`) | 3 of 3 | 5 | 102 ms | works |
| `electron-todo` | Electron 39 without the flag | 0 of 3 (frame only) | 1 | | the result names the flag |
| `webkit-counter` | WebKitGTK through `wry`, the Tauri case (`gtk`) | 2 of 2 | 3 | 81 ms | works |
| `swing-calc` | Java Swing through the ATK wrapper (`J2SE-access-bridge`) | 4 of 5, the frame has no extents | 4 | 101 ms | works |
| `tk-counter` | Tk | 0 of 2, no tree | 0 | 101 ms | not possible; the result says to use coordinates |

Observations:

- **Every toolkit with a bridge is usable.** Names, roles and bounds matched the controls, and a click by id landed inside the element in every case. Swing's frame reports extents of -1, so its controls appear at the top level of the result; this does not affect clicking.
- **Electron needs the renderer flag.** Without `--force-renderer-accessibility` only the window frame exists; `ACCESSIBILITY_ENABLED`, `org.a11y.Status.IsEnabled` and `ScreenReaderEnabled` do not change that, so the result names the flag instead.
- **Pruning matters.** The raw AT-SPI tree of the `form-web` window has about 200 objects, mostly unnamed panels and hidden parts of the browser frame; the result has 12. A GTK file chooser dialog, the largest tree in the live check, had 40 elements.
- **The text form halves the size.** The rendered tree of `gtk-notes` is 411 characters against 904 for the same nodes as JSON, the file chooser 2,128 against 4,750, so a typical tree costs 100–600 tokens, less than half a screenshot.
- **The image grew by `python3-gi` and two Qt libraries** (`libxcb-icccm4`, `libxcb-keysyms1`, without which Qt 6 cannot load its `xcb` plugin), a few megabytes.

### Live check

`infra/bench/desktop/live_check.py --task notes` with the GTK fixture: add two notes, check Pinned, open File > Export… and cancel the dialog. The prompt does not mention the tree.

| Model | Runs | Passed | `desktop_tree` calls | Clicks by id / all clicks | Prompt tokens (cached) | Time |
|---|---|---|---|---|---|---|
| `deepseek-v4.1-flash:free` (TokenHarbor) | 3 | 3 | 2–3 | 19 / 23 | 169–188k (156–165k) | 53–67 s |
| `mimo-v2.6-flash:free` (TokenHarbor) | 1 | 1 | 0 | 0 / 8 | 182k (137k) | 118 s |

DeepSeek read the tree after the window appeared, clicked the entry, the button and the check box by id, opened the File menu by id, clicked the menu item by coordinates (the menu was not in the tree it had read) and read the tree again to find Cancel in the dialog. MiMo never called the tool and solved the task from screenshots. Whether the tree saves steps or tokens is the ablation of phase 1.6.

### Typing with the rebuilt image

The image rebuilt for this phase came with Chromium 156, and the runner test that types `Привет 😀 café` into Chromium failed in five of eight runs: characters came out as the first remapped one (`ППивет`, `cafП`). `xdotool type` binds each character missing from the keyboard map to one spare key code in turn, and this Chromium kept the first mapping. It failed the same way with the accessibility bridge disabled, so it is not caused by the tree. `desktop_type` now binds all missing characters of the text to free key codes in one change and waits 150 ms before typing; the test then passed eight runs of eight.

## Phase 1.6: benchmark

Measured on 2026-11-25 and 2026-11-26 with `infra/bench/desktop/run.py` against a local backend (`bootRun`); the tables come from `infra/bench/desktop/report.py` (commands in `infra/bench/desktop/README.md`). Every run starts a fresh bench device inside the `gui` image as the session's executor, so each task begins on an empty display with the fixtures copied into `/workspace`. The agent gets the task in an Agent session, the harness polls the application's own state (a JSON file or the window title), and the run passes when the task's check holds at the end. Unless a section says otherwise the screenshot policy was the phase 1.3 default (`keep 3, block 4`) and the tree tool was on.

### Tasks and fixtures

Seven fixture applications in `infra/bench/desktop/apps/`; five of them have three tasks each, the `matrix` group of 15 tasks:

| Kind | What it asks | Examples |
|---|---|---|
| Single action | One input and a look at the result | click Increment once (`tk-counter`, `tauri-counter`), check Pinned (`gtk-notes`), drag list item D above A (`form-web`), add the todo milk (`electron-todo`) |
| Sequence | Five to eleven inputs across fields, menus and dialogs | two notes, Pinned, File > Export… and Cancel (`gtk-notes`); register a user and close the confirmation (`form-web`); three todos, one done, Hide done (`electron-todo`) |
| Trap | Something the prompt does not mention gets in the way | a modal milestone dialog at count 3 (`tk-counter`), an in-page lock at 3 (`tauri-counter`), "file exists, replace?" on export (`gtk-notes`), a username field that rejects spaces (`form-web`), a "What's new" overlay that covers the form 1.5 seconds after the start (`electron-todo`) |

`qt-settings` and `swing-calc` add one sequence each for the tree ablation. Every task has a reference solution (`reference.py`) that drives the application with `xdotool` and the runner's accessibility reader inside the image without a model; `run.py --verify-fixture` solved all 17 tasks and the click accuracy screens before any model ran.

### Models

| Provider | Model | Used for |
|---|---|---|
| Experiential Labs | `gpt-6-luna` | matrix (one repetition: the free plan's 1M tokens a day hold about one), grid, calibration |
| TokenHarbor | `deepseek-v4.1-flash:free` | matrix (three repetitions), grid, both ablations, calibration |
| TokenHarbor | `mimo-v2.6-flash:free` | matrix (three repetitions), grid, calibration |
| Kilo Gateway | `nvidia/nemotron-3.5-super-vl:free` | matrix (one repetition); its grid and calibration runs failed with `provider_unauthorized` after Kilo invalidated the key, so it has no grounding or calibration numbers |

Screened with one `tk-single` run and dropped: Kilo's `particle-ai/particle-1:free` and `inclusionai/ling-3.2-flash:free` (provider errors), `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free` (it answers coordinates as fractions between 0 and 1, hit 1 of 6 grid buttons and needed 40 requests for one click), and the Mistral models (`402 billing_no_api_budget_configured` until the account activates a plan).

### Success by model and task kind (metric 1)

| Provider | Model | Single | Sequence | Trap | All | Rate |
|---|---|---|---|---|---|---|
| Experiential Labs | `gpt-6-luna` | 5/5 | 5/5 | 5/5 | 15/15 | 100 % |
| Kilo Gateway | `nvidia/nemotron-3.5-super-vl:free` | 5/5 | 5/5 | 4/4 | 14/14 | 100 % |
| TokenHarbor | `deepseek-v4.1-flash:free` | 15/15 | 15/15 | 15/15 | 45/45 | 100 % |
| TokenHarbor | `mimo-v2.6-flash:free` | 14/15 | 15/15 | 15/15 | 44/45 | 98 % |

Failures and reruns:

- `deepseek-v4.1-flash:free`, `web-sequence`, one run: the model registered the user, then closed the browser window instead of the confirmation dialog, so the page state could not be read at the end. The run is not counted (`matrix-unreadable`) and the task was run a fourth time, which passed; read strictly, it is one more failure of 46.
- `mimo-v2.6-flash:free`, `web-single`, one run: five drags from item D to points near A left the list unchanged; the model then tried the keyboard and reached the harness's 10-minute limit after 26 calls. Its other two runs, and every run of the other models, reordered the list with one drag.
- `gpt-6-luna`: two runs (`gtk-trap`, `web-trap`) were cut by the free plan's limit of 10 requests a minute in the middle of the task and were run again with a pause between runs; both passed. The cut runs are kept apart (`matrix-el-ratelimited`) and not counted.
- `nemotron-3.5-super-vl:free`, `tauri-trap`: the provider failed both attempts (a rate limit, then the invalidated key) before the first request; it is the missing run of 15.

Every model that could be run solved every kind of task. All traps were handled from the screenshots alone (the milestone dialog, the lock, the replace question, the validation message under the field and the overlay); no model clicked through a trap blindly, and no trap run failed. The two real failures were a drag that did not take and a model closing the window it was meant to keep open.

### Click accuracy (metric 2)

`grid-targets` fills the screen with 48 labelled buttons at seeded random positions and sizes (70–120 by 24–34 pixels; `griddense` packs them tighter). The model takes one screenshot and clicks six named buttons with `observe: false`, one call each, without looking again, so each click is a pure grounding decision. A hit lands inside the named button; dx and dy are the distances from the button's centre.

| Model | Coordinates | Buttons | Display | Runs | Hits | Accuracy | Median dx | Median dy | Wrong button | Not clicked | Largest error px |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `deepseek-v4.1-flash:free` | normalized | dense | 1280x800 | 2 | 7/12 | 58 % | 1.8 | 5.2 | 0 | 0 | 164 |
| `deepseek-v4.1-flash:free` | normalized | regular | 1280x800 | 2 | 11/12 | 92 % | 1.2 | 1.2 | 0 | 0 | 28 |
| `deepseek-v4.1-flash:free` | pixels | dense | 1280x800 | 2 | 10/12 | 83 % | 1.2 | 1.2 | 0 | 0 | 125 |
| `deepseek-v4.1-flash:free` | pixels | dense | 1920x1080 | 2 | 8/12 | 67 % | 0.8 | 0.8 | 0 | 0 | 140 |
| `deepseek-v4.1-flash:free` | pixels | regular | 1024x768 | 1 | 6/6 | 100 % | 0.8 | 0.8 | 0 | 0 | 10 |
| `deepseek-v4.1-flash:free` | pixels | regular | 1280x800 | 2 | 11/12 | 92 % | 1.2 | 1.0 | 0 | 0 | 41 |
| `deepseek-v4.1-flash:free` | pixels | regular | 1920x1080 | 1 | 6/6 | 100 % | 2.2 | 1.5 | 0 | 0 | 14 |
| `gpt-6-luna` | normalized | dense | 1280x800 | 2 | 12/12 | 100 % | 0.5 | 0.8 | 0 | 0 | 2 |
| `gpt-6-luna` | normalized | regular | 1280x800 | 2 | 10/12 | 83 % | 1.5 | 0.5 | 0 | 0 | 256 |
| `gpt-6-luna` | pixels | dense | 1280x800 | 2 | 12/12 | 100 % | 0.5 | 0.5 | 0 | 0 | 2 |
| `gpt-6-luna` | pixels | dense | 1920x1080 | 2 | 12/12 | 100 % | 0.5 | 0.5 | 0 | 0 | 2 |
| `gpt-6-luna` | pixels | regular | 1024x768 | 1 | 6/6 | 100 % | 0.5 | 0.5 | 0 | 0 | 2 |
| `gpt-6-luna` | pixels | regular | 1280x800 | 2 | 12/12 | 100 % | 0.5 | 0.5 | 0 | 0 | 2 |
| `gpt-6-luna` | pixels | regular | 1920x1080 | 1 | 6/6 | 100 % | 1.0 | 0.5 | 0 | 0 | 2 |
| `mimo-v2.6-flash:free` | normalized | dense | 1280x800 | 2 | 12/12 | 100 % | 2.8 | 1.2 | 0 | 0 | 5 |
| `mimo-v2.6-flash:free` | normalized | regular | 1280x800 | 2 | 12/12 | 100 % | 1.2 | 1.0 | 0 | 0 | 54 |
| `mimo-v2.6-flash:free` | pixels | dense | 1280x800 | 2 | 12/12 | 100 % | 2.5 | 1.0 | 0 | 0 | 6 |
| `mimo-v2.6-flash:free` | pixels | dense | 1920x1080 | 2 | 12/12 | 100 % | 1.5 | 0.5 | 0 | 0 | 7 |
| `mimo-v2.6-flash:free` | pixels | regular | 1024x768 | 2 | 12/12 | 100 % | 3.8 | 1.5 | 0 | 0 | 36 |
| `mimo-v2.6-flash:free` | pixels | regular | 1280x800 | 2 | 12/12 | 100 % | 1.2 | 1.0 | 0 | 0 | 51 |
| `mimo-v2.6-flash:free` | pixels | regular | 1920x1080 | 2 | 12/12 | 100 % | 2.0 | 1.5 | 0 | 0 | 11 |

Observations:

- **`gpt-6-luna` and `mimo-v2.6-flash:free` are exact.** Every pixel click hit, within 1–4 pixels of the centre in the median, on every display size, including 1920x1080, which the model sees scaled to 1280x720 and the backend maps back.
- **DeepSeek misses on dense screens.** It hit 92–100 % of regular buttons but 83 % of dense ones at 1280x800 and 67 % at 1920x1080. A miss is not slightly off: the median error of the clicks is about one pixel, while a miss lies 40–160 pixels away on an empty spot, so the model picked the wrong place, not the wrong pixel. No click landed on a wrong button.
- **The 0–1000 grid helps no model here.** With `normalized`, DeepSeek fell to 58 % on dense screens and `gpt-6-luna` had one 256-pixel miss; MiMo was exact either way. All three test models keep `coordinate_system = pixels`, the default.
- **Scaling costs little for good models.** The 1920x1080 display was as accurate as 1280x800 for `gpt-6-luna` and MiMo; for DeepSeek the dense buttons became small enough to be missed more often.

### Cost and time per task (metric 3)

Medians over the matrix runs; time is the whole run from the first message to the idle session, split into the model's time (requests, including waits after rate limits) and the tools' time, of which settle and `desktop_wait` are shown separately.

| Model | Runs | Requests | Tool calls | Screenshots | Prompt tokens | Cached | Output tokens | Time s | Model s | Tools s | Settle s | Wait s | s per request |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `gpt-6-luna` | 15 | 10 | 9 | 7 | 67.7k | 77 % | 317 | 32 | 22 | 4.8 | 1.8 | 0.4 | 2.8 |
| `nvidia/nemotron-3.5-super-vl:free` | 14 | 16 | 14 | 10 | 162.2k | 56 % | 1,833 | 106 | 92 | 6.4 | 2.9 | 0.4 | 6.7 |
| `deepseek-v4.1-flash:free` | 45 | 11 | 11 | 6 | 94.0k | 91 % | 1,010 | 36 | 32 | 4.5 | 1.5 | 0.4 | 3.3 |
| `mimo-v2.6-flash:free` | 45 | 11 | 10 | 7 | 90.5k | 86 % | 631 | 92 | 72 | 5.1 | 1.8 | 0.4 | 7.2 |

At a cached price of a tenth, a matrix task costs the equivalent of 17k uncached prompt tokens with DeepSeek, 19k with `gpt-6-luna`, 27k with MiMo and 82k with Nemotron. Model time is 69–90 % of the wall-clock time for every model; settling adds 1.5–2.9 seconds a task, so the 300 ms settle delay is not what makes GUI work slow. `gpt-6-luna` and DeepSeek take about 3 seconds per request, a pace a user can follow in the live view; the free MiMo and Nemotron endpoints take about 7. Nemotron's provider served only 56 % of the prompt from its cache against 77–91 % for the others, which makes it the most expensive model per task.

### Calibration of the image estimate

The prompt tokens of a one-step Ask turn with one uploaded image, minus the same turn without it (`run.py --calibrate`):

| Provider | Model | 1280x800 | 1280x720 | 1024x768 | 640x400 | Pixels per token |
|---|---|---|---|---|---|---|
| Experiential Labs | `gpt-6-luna` | 1,201 | 1,105 | 922 | 313 | 818–853 |
| TokenHarbor | `mimo-v2.6-flash:free` | 1,002 | 882 | 770 | 242 | 1,021–1,058 |
| TokenHarbor | `deepseek-v4.1-flash:free` | 642 | 578 | 496 | 200 | 1,280–1,595 |
| Estimate at 750 pixels per token | | 1,366 | 1,229 | 1,049 | 342 | |

The providers count between 818 and 1,595 pixels per token, so the estimate of 750 was 9–113 % too high. `pointer.desktop.pixels-per-token` is now 800, just below the smallest ratio measured: the estimate still never undercounts a test model, which keeps compaction thresholds on the safe side, and overcounts `gpt-6-luna` by 2–7 % instead of 9–14 %.

### Share of screenshots in the prompt (metric 4)

From the masking runs below (DeepSeek, two sequence tasks of 9–12 screenshots): with every screenshot kept a request carried 9.6k prompt tokens on average, with only the newest one 7.4k. At DeepSeek's 642 tokens per 1280x800 screenshot the images are about 30 % of an average request when nothing is masked (2.2k of screenshots that masking removes plus the visible one), the desktop tool schemas 15 % (1.4k, phase 1.2), and the system prompt, the other tools, the results and the conversation the rest. The share grows with the length of the task, since every kept screenshot stays in every later request.

### Masking ablation (metric 5)

DeepSeek on `gtk-sequence` and `electron-sequence`, two runs each per policy; the backend was restarted with `POINTER_DESKTOP_KEEP_IMAGES` and `POINTER_DESKTOP_IMAGE_MASK_BLOCK` for each policy. Runs of one policy differ in length (the model decides how often it looks), so the figures are per request. The cost-equivalent counts cached tokens at a tenth; prefix reuse is the share of each request's prefix that matched the previous request, from the usage ledger; a repeated action is the same input call with the same arguments twice in a row.

| Screenshots kept | Passed | Requests | Screenshots | Prompt per request | Uncached per request | Cost-equivalent per request | Prefix reuse | Repeated actions |
|---|---|---|---|---|---|---|---|---|
| all | 4/4 | 64 | 37 | 9.63k | 0.60k | 1.51k | 92 % | 0 |
| keep 3, block 4 (phase 1.3 default) | 4/4 | 70 | 41 | 9.13k | 0.76k | 1.60k | 88 % | 0 |
| keep 3, block 1 | 4/4 | 64 | 41 | 8.19k | 1.51k | 2.18k | 73 % | 0 |
| keep 1, block 4 (new default) | 4/4 | 64 | 39 | 8.14k | 0.64k | 1.39k | 88 % | 0 |
| keep 1, block 1 | 4/4 | 65 | 39 | 7.40k | 0.90k | 1.55k | 86 % | 0 |

Observations:

- **No policy cost success or caused repeats.** Every run passed and no model repeated an input, so on tasks of this length the model never needed a screenshot older than the newest: each screenshot shows the whole current state, unlike old text results, whose content stays relevant.
- **`keep 1, block 4` is the cheapest and sends 15 % less.** It sends 8.1k tokens per request against 9.6k with everything kept; its cost-equivalent is 8 % below keeping everything and 13 % below the old default. The old default saved only 5 % of the context here, because a task of ten screenshots moves its boundary at most once.
- **Per-step masking breaks the cache when it keeps more than one screenshot.** `keep 3, block 1` masks the screenshot three steps back at every step, so every request misses the cache from there on: prefix reuse fell to 73 % and the uncached tokens doubled, which made it the most expensive policy, as phase 1.3 predicted. With `keep 1` the newly masked screenshot is always the previous step's, so `block 1` costs less (86 % reuse) but still more than `block 4`.

`pointer.desktop.keep-images` is now 1, with `image-mask-block` 4. The ablation used one model and tasks of up to twelve screenshots; a model that needs to compare with an earlier screen can still describe it in its own words or look again, and deployments that want more keep `POINTER_DESKTOP_KEEP_IMAGES`.

### Tree ablation (metric 6)

DeepSeek on one sequence task per toolkit, two runs each, with `desktop_tree` available (`pixels and tree`) and with its tool row disabled (`pixels only`, `run.py --no-tree`). The prompt does not mention the tree. Requests, screenshots, prompt tokens and time are medians; tree calls and clicks are totals over both runs.

| Model | Toolkit | Tools | Passed | Requests | Screenshots | Prompt tokens | Time s | Tree calls | Clicks by id |
|---|---|---|---|---|---|---|---|---|---|
| `deepseek-v4.1-flash:free` | Chromium | pixels only | 2/2 | 11 | 8 | 96.4k | 41 | 0 | 0/9 |
| `deepseek-v4.1-flash:free` | Chromium | pixels and tree | 2/2 | 12 | 8 | 92.1k | 53 | 3 | 8/9 |
| `deepseek-v4.1-flash:free` | Electron | pixels only | 2/2 | 14 | 11 | 113.6k | 47 | 0 | 0/11 |
| `deepseek-v4.1-flash:free` | Electron | pixels and tree | 2/2 | 18 | 9 | 153.1k | 73 | 6 | 16/16 |
| `deepseek-v4.1-flash:free` | GTK | pixels only | 2/2 | 16 | 10 | 141.3k | 46 | 0 | 0/16 |
| `deepseek-v4.1-flash:free` | GTK | pixels and tree | 2/2 | 18 | 10 | 169.5k | 86 | 8 | 15/15 |
| `deepseek-v4.1-flash:free` | Qt | pixels only | 2/2 | 16 | 8 | 128.4k | 60 | 1 | 0/11 |
| `deepseek-v4.1-flash:free` | Qt | pixels and tree | 2/2 | 14 | 6 | 121.4k | 53 | 4 | 9/9 |
| `deepseek-v4.1-flash:free` | Swing | pixels only | 2/2 | 14 | 8 | 114.0k | 44 | 0 | 0/7 |
| `deepseek-v4.1-flash:free` | Swing | pixels and tree | 2/2 | 12 | 6 | 93.6k | 41 | 2 | 6/6 |
| `deepseek-v4.1-flash:free` | Tauri | pixels only | 2/2 | 9 | 6 | 61.9k | 30 | 0 | 0/8 |
| `deepseek-v4.1-flash:free` | Tauri | pixels and tree | 2/2 | 11 | 6 | 82.6k | 63 | 4 | 8/8 |
| `deepseek-v4.1-flash:free` | Tk | pixels only | 2/2 | 13 | 6 | 97.6k | 43 | 0 | 0/8 |
| `deepseek-v4.1-flash:free` | Tk | pixels and tree | 2/2 | 8 | 5 | 65.9k | 26 | 0 | 0/4 |

Observations:

- **The tree did not change success.** Both arms solved every task. One `pixels only` run of `electron-sequence` ended its turn after one sentence and no tool call; that is a stop of the model before it started, unrelated to the tree, and the task was run again (the stopped run is kept as `tree-off-stopped`).
- **Where a tree exists, the model clicks by id.** With the tree DeepSeek read it 1–4 times a task and made 62 of 63 clicks by `elementId` on GTK, Qt, Chromium, Electron, Tauri and Swing. It never called the tool on Tk, where there is no tree.
- **It does not save tokens or time on small applications.** Reading the tree is an extra request, and the model still looks at the screen after acting, so the two arms needed about as many requests and screenshots; the tree arm used fewer prompt tokens on Qt, Chromium and Swing and more on GTK, Electron and Tauri. On screens this simple DeepSeek's pixel clicks were already right.
- **What the tree buys is independence from grounding.** A click by id does not depend on where the model thinks a control is, which matters where grounding fails: on the dense grid above DeepSeek missed a sixth of the buttons by pixels. The tree stays on.

### Settle delay

The reference solutions record, after every input, when the screen stopped changing: tiles of 32x32 pixels are compared for 2.5 seconds, tiles that also change while the application is idle are ignored, and a tile that flips between two values (a blinking caret) counts at its first change.

| Toolkit | Action | Actions | Median ms | 90th percentile ms | Largest ms | Over 300 ms |
|---|---|---|---|---|---|---|
| Chromium | click | 24 | 10 | 116 | 120 | 0 |
| Chromium | drag | 2 | 216 | 216 | 216 | 0 |
| Chromium | key | 2 | 8 | 9 | 9 | 0 |
| Chromium | type | 12 | 8 | 1,017 | 1,017 | 5 |
| Electron | click | 20 | 8 | 9 | 10 | 0 |
| Electron | key | 6 | 8 | 8 | 9 | 0 |
| Electron | type | 12 | 8 | 16 | 16 | 0 |
| GTK | click | 28 | 113 | 292 | 300 | 0 |
| GTK | key | 4 | 236 | 464 | 464 | 2 |
| GTK | type | 8 | 4 | 144 | 158 | 0 |
| Qt | click | 10 | 8 | 9 | 9 | 0 |
| Qt | key | 2 | 7 | 7 | 7 | 0 |
| Qt | type | 4 | 8 | 8 | 8 | 0 |
| Swing | click | 6 | 16 | 17 | 21 | 0 |
| Swing | key | 4 | 4 | 8 | 8 | 0 |
| Swing | type | 4 | 4 | 8 | 8 | 0 |
| Tauri | click | 26 | 8 | 9 | 1,765 | 3 |
| Tauri | type | 3 | 40 | 45 | 45 | 0 |
| Tk | clickState | 22 | 8 | 10 | 119 | 0 |
| Tk | key | 2 | 0 | 0 | 0 | 0 |
| Tk | type | 2 | 8 | 8 | 8 | 0 |

The input operations return their screenshot 300 ms after the last input event. Clicks settled within 120 ms in every toolkit except GTK, where a button's press animation takes up to 300 ms; GTK's menus and file chooser opened by a key took 460 ms; the values above one second in Chromium and Tauri are a caret that first appears after its blink interval, not a slow interface. In the model runs an observed input was followed by another screenshot with no input in between in 4 of 693 cases (two Electron, one Chromium, one Tk), so the models almost never needed a second look. `pointer.desktop.settle-ms` stays 300: 500 ms for the GTK menus would add 0.2 seconds to every input of every toolkit, while a model that sees a half-open menu looks again.

### Defaults after the benchmark

| Setting | Before | After | Reason |
|---|---|---|---|
| `pointer.desktop.keep-images` / `image-mask-block` | 3 / 4 | 1 / 4 | Cheapest policy, 15 % smaller requests, no loss of success, no repeats |
| `pointer.desktop.pixels-per-token` | 750 | 800 | Just below the smallest provider ratio measured (818) |
| `pointer.desktop.settle-ms` | 300 | 300 | 4 extra screenshots in 693 observed inputs |
| Display size and image limit | 1280x800, 1280x800 | unchanged | 1920x1080 scaled down lost DeepSeek's dense clicks; the good grounders were exact at both |
| Coordinate convention of the test models | pixels | pixels | No model was more accurate on the 0–1000 grid |
| `desktop_tree` | on | on | Used for nearly every click where it exists, no cost to success elsewhere |
| Tool form | eleven tools | eleven tools | The schemas are 15 % of a request and cached after the first step, and wrong arguments were rare (below), so the single-tool form was not built |

Of 2,411 desktop calls by the four models, 21 failed with `invalid_arguments`, all by MiMo and all on `desktop_wait`: its nested `untilLog` object came back with MiMo's own tool-call markup inside the value (`"untilLog": "<parameter=processId>p1"`). MiMo corrected the call each time. A flat argument would avoid this; a single tool with an `action` argument would nest every argument one level deeper.

### Isolation levels (metric 7)

| | Container display (this feature) | Background control through accessibility APIs (macOS assistants) | Foreground control on the user's display (Windows assistants) |
|---|---|---|---|
| Interference with the user | None: own display, pointer and keyboard focus | Low: input goes to the target application without moving the user's pointer, but it acts on the user's real applications | High: one cursor and one focus shared with the user, who has to stop working |
| Reproducibility | High: a fresh display in a recreated container per run (every run above started empty) | Low: depends on the state of the user's applications | Low: the same, plus whatever else is on the screen |
| Applications covered | Anything that runs on Linux under X11; measured here: Tk, GTK, Qt, Chromium, Electron, Tauri (WebKitGTK), Swing | Applications of the platform that expose accessibility | Everything on the screen |
| Platform fidelity | Linux builds and engines (WebKitGTK instead of WebView2 for Tauri) | The real platform | The real platform |
| Runs on a server without the user | Yes | No | No |

The first column is measured in this document; the other two summarise the vendors' published descriptions of their computer-use features and are not measured here.

### Resource cost (metric 8)

Memory (`memory.current`, page cache included) and the peak of processes and threads of the container while the reference solution ran each fixture, on top of the display stack of phase 1.1:

| Fixture | Toolkit | Memory | Processes and threads, peak |
|---|---|---|---|
| `tk-counter` | Tk | 62 MB | 19 |
| `gtk-notes` | GTK 3 | 72 MB | 32 |
| `tauri-counter` | Tauri 2 (WebKitGTK) | 102 MB | 87 |
| `qt-settings` | Qt 6 | 105 MB | 30 |
| `electron-todo` | Electron 39 | 155 MB | 133 |
| `form-web` | Chromium 156 | 184 MB | 133 |
| `swing-calc` | Java Swing | 226 MB | 59 |

Every fixture fits in the runner's limits (4 GB, 512 processes) with room for a build next to it, so no separate GUI limit is needed. The `gui` image needs 3.2 GB of disk unpacked, which the current production host (1 vCPU, 913 MB of memory, 1.9 GB of free disk) does not have; GUI sessions are not offered there until it grows. On a host with the disk for the image, the number of GUI sessions is bounded by the runner's memory limit, not by the display.

### What a pass proves (metric 10)

Not measured in stage 1. Running the same `tauri-counter` task against the Windows build needs the desktop tools on the user's machine, which is stage 2 ([stage-2-local-sessions.md](../features/f7-gui-sandbox/stage-2-local-sessions.md)); a pass here shows that the logic and the layout of the Linux build work, as the scope of stage 1 says.

## Stage 2, phase 2.1: sandbox of a local session

Live check of [ADR 0031](../adr/0031-sandbox-for-local-sessions.md) on 2026-11-26 with `infra/bench/desktop/sandbox_check.py`: the bench device ran natively on Windows 11 as the local executor of a project with the sandbox on, the backend ran on the host with `POINTER_CONTAINER_ENABLED=true` and the default sandbox image `pointer-runner:gui`. The task asks the agent to install `x11-apps` in the sandbox, compute 17 × 23 in `xcalc` by clicking and run `cmd /c ver` on the user's computer.

| Model | Result | Tool calls | Screenshots | First sandbox call | Turn |
|---|---|---|---|---|---|
| `deepseek-v4.1-flash:free` (TokenHarbor) | Pass: `391` from `xcalc`, `Microsoft Windows [Version 10.0.26200.9974]` from the local `run_command` | 34 (1 `sandbox_run`, 1 `run_command`, 23 clicks) | 24 | 7.5 s, a cold container start plus `apt-get update` and the install | 182 s |

The routing held in both directions: every desktop call and `sandbox_run` reached the sandbox's runner, `run_command` reached the Windows device and answered from Windows. A second run from the desktop client preview with `mimo-v2.6-flash:free`, the preview's browser executor as the local device, wrote a Tk script with `sandbox_run`, launched and clicked it in 5 calls and answered correctly; the Desktop panel showed the sandbox's display live. Deleting either session removed its container and volume and revoked its runner device. The project's own files do not reach the sandbox until phase 2.2, so the task uses only what the agent installs or writes there.

## Stage 2, phase 2.2: project sync

Live check of `sandbox_sync` ([ADR 0032](../adr/0032-project-snapshots-through-transfers.md)) on 2026-11-26, same setup as phase 2.1 (bench device on Windows 11, backend on the host, `pointer-runner:gui` rebuilt with the new runner), model `deepseek-v4.1-flash:free` (TokenHarbor), auto-approve on.

| Task | Project | Result | Syncs | Files sent | Content / archive | First sync | Second sync | Turn |
|---|---|---|---|---|---|---|---|---|
| `--task sync` | 5 files: `check.py`, `version.txt`, `.gitignore` with `build/`, `.env`, `build/output.bin` | Pass: `version=1 env=False ignored=False`, then `version=2 env=False ignored=False` after a local `write_file` and a second sync | 2 | 3 (`.env` left out and named, `build/` ignored) | 173 B / 288 B | 0.8 s, a cold container start included | 0.0 s | 18.2 s |
| `--task measure` | this repository's working copy, with `node_modules`, `target` and other ignored folders present | Pass | 2 | 688 | 4.1 MB / 1.6 MB | 3.5 s, a cold container start included | 0.3 s | 12.1 s |

The sandbox's project folder, listed with `docker exec` after the turn, held exactly the sent files and the new `version.txt`; the second sync reported `firstSync: false`. A first run of the small task with its workspace inside this repository passed as well but sent no `.env` and named none, because the repository's own `.gitignore` already ignores `.env` and applies to a folder inside it; the run outside the repository is the one that exercises the secret filter. From the desktop client preview the browser executor packed its mock project with `CompressionStream`, the real sandbox runner unpacked it, and the agent ran `python3 src/main.py` there and got `5`; the approval panel asked before the sync and before the command, and the sync card shows the result text.

A sync uploads the whole project every time: about 1.6 MB for this 700-file repository, as much as some 50 screenshots of about 30 KB. A warm sync takes well under a second, so the agent can sync before every build without a delta transfer.
