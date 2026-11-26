# Desktop sandbox harness

Fixtures and scripts for [F7](../../../docs/features/f7-gui-sandbox/README.md). Results are in [docs/benchmarks/desktop.md](../../../docs/benchmarks/desktop.md).

| Path | Purpose |
|---|---|
| `apps/tk-counter/app.py` | Tk window with a button, an entry, a list and `ctrl+s`; writes its state and the screen bounds of its widgets to the JSON file given as the first argument and prints `tk-counter ready` once mapped. With `--milestone=N` a modal dialog appears when the count reaches N and blocks the window until OK. Tk has no accessibility tree, so it is also the pixels-only case. |
| `apps/form-web/index.html` | Page for Chromium with a list reordered by HTML5 drag and drop, a text field and a registration form whose username rejects spaces ("Use a dash instead") and whose success opens a modal `<dialog>`; the page title carries `{"order", "text", "users", "dialog"}`, so a test reads the result from the window title. |
| `apps/webkit-counter/` | A `wry` window (the webview layer of Tauri) on WebKitGTK with a counter button; the title shows the count. Used by the runner tests. |
| `apps/tauri-counter/` | A real Tauri 2 application (built with `cargo`, no Tauri CLI): a name field with Save name, a count and Increment; the page reports its state through a Tauri command that writes the JSON file given as the first argument and prints `tauri-counter ready`. With `--lock-at=N` an in-page dialog locks the counter at N until Unlock. `icons/icon.ico` wraps the PNG icon for the Windows build. |
| `apps/gtk-notes/app.py` | GTK 3 window with a File menu (New note, Export… with a file chooser that writes the notes and asks before replacing a file), a note entry, an Add note button, a Pinned check box and a list; writes its state to the JSON file given as the first argument and prints `gtk-notes ready`. The main accessibility tree fixture. |
| `apps/qt-settings/app.py` | Qt 6 (PySide 6 from `pip`) window with two tabs, a Dark mode check box, a Font size spin box, a Proxy field and an Apply button; writes its state like `gtk-notes` and prints `qt-settings ready`. |
| `apps/electron-todo/` | Electron 39 window with a todo field, an Add button, a done box per todo, a Hide done check box and a list; the title carries `{"todos", "done", "hideDone", "overlay"}` and the log `electron-todo ready`. With `--whats-new` a full-window "What's new" overlay covers the form 1.5 seconds after the start. Its web content has an accessibility tree only with `--force-renderer-accessibility`. |
| `apps/grid-targets/app.py` | Full-screen Tk canvas with 48 labelled buttons at seeded random positions and sizes (70–120 by 24–34 pixels) for the click accuracy test; records every click with the button it hit and writes the layout. |
| `apps/swing-calc/SwingCalc.java` | Java Swing window with two number fields, a Plus button and a result label, run as a single source file (`java SwingCalc.java <state file>`); writes its state and prints `swing-calc ready`. |
| `sandbox_check.py` | Live check of the sandbox of a local session (F7 stage 2): runs the Windows build of the bench device as the local executor of the project `sandbox-check` with the sandbox on, and asks the agent to install and drive `xcalc` in the sandbox and run a command on the local machine. Records the routing, the time of the first sandbox call (the container's cold start) and success. |
| `transfer_check.py` | Metric 10 of F7 (stage 2, phase 2.4): `--side linux` gives the Tauri tasks to an agent in the sandbox of a Windows local session, which syncs and builds the project there first; `--side windows` replays the same tasks' reference solutions against the native Windows build with `windows_replay.ps1`. Both read the application's state file and apply the task's check. |
| `windows_replay.ps1` | Replays a reference solution of `tasks.py` (`click`, `type`, `waitTree` by accessible name and role) against a window on Windows through UI Automation: buttons by their invoke pattern, text fields by their value pattern. |
| `live_check.py` | Live check with real models: runs the bench device inside the `gui` image and gives the agent a task on `tk-counter`, or with `--task notes` on `gtk-notes`, where the accessibility tree is available (phase 1.5). The default task (phase 1.2) sends a second message while the turn runs and records whether the provider accepted the screenshot message after the tool results and two user messages in a row; `--task clicks` (phase 1.3) takes a screenshot per click for the image masking comparison; `--schema-cost` compares the prompt tokens of a one-step turn with and without the desktop tools. |
| `tasks.py` | The benchmark tasks: three kinds (single action, sequence, trap) on `tk-counter`, `gtk-notes`, `form-web`, `electron-todo` and `tauri-counter`, a sequence on `qt-settings` and `swing-calc`, the click accuracy tasks `grid-<size>-<seed>`, each with its prompt, its check on the application's state and a reference solution. |
| `reference.py` | Runs inside the `gui` image without a model: starts a display like the runner, solves a task with `xdotool` and the runner's accessibility reader, checks it, and with `--settle` measures how long the screen keeps changing after each input. |
| `run.py` | The benchmark harness (phase 1.6): `--verify-fixture` runs the reference solutions; otherwise it starts a fresh bench device inside the `gui` image for every run, gives the task to each `--model` in a server-style Agent session and stores the outcome, the calls, the usage and the timing in `results/bench-<label>-<time>.json`. `--calibrate` measures the prompt tokens of one image per size and model. |
| `report.py` | Turns the results into the tables of [desktop.md](../../../docs/benchmarks/desktop.md). |
| `measure.sh`, `measure-inner.sh` | Image size, display start-up time, memory and process counts of the display, Chromium and Tk, and the process peak of Chromium next to a parallel `cargo build`. |

## Build the image

```sh
cd desktop/src-tauri
docker build -f ../../infra/runner/Dockerfile --target gui -t pointer-runner:gui .
```

## Run the runner tests inside the image

From the repository root (in Git Bash on Windows prefix `docker run` with `MSYS_NO_PATHCONV=1` and give the repository as a Windows path):

```sh
docker run --rm -v "$PWD:/repo" -v pointer-webkit-target:/target -w /repo/infra/bench/desktop/apps/webkit-counter \
  --entrypoint cargo pointer-runner:gui build --release --target-dir /target

docker run --rm --init --shm-size 512m -v "$PWD:/repo" -v pointer-test-target:/target -v pointer-webkit-target:/webkit \
  -e CARGO_TARGET_DIR=/target -e POINTER_DESKTOP_TESTS=1 -e POINTER_WEBKIT_COUNTER=/webkit/release/webkit-counter \
  -w /repo/desktop/src-tauri --entrypoint cargo pointer-runner:gui test -p pointer-executor -p pointer-runner
```

Without `POINTER_DESKTOP_TESTS` the display tests return early, so `cargo test` on a developer machine skips them; without `POINTER_WEBKIT_COUNTER` the WebKit tests are skipped.

The accessibility tree tests of phase 1.5 also cover Qt and Electron when their packages are installed into volumes once:

```sh
docker run --rm -v pointer-qt-venv:/qt --entrypoint sh pointer-runner:gui \
  -c 'python3 -m venv /qt && /qt/bin/pip install PySide6-Essentials==6.10.1'

docker run --rm -v "$PWD/infra/bench/desktop/apps/electron-todo:/app" -v pointer-electron-todo-modules:/app/node_modules \
  -w /app --entrypoint npm pointer-runner:gui ci
```

Then add `-v pointer-qt-venv:/qt -v pointer-electron-todo-modules:/repo/infra/bench/desktop/apps/electron-todo/node_modules -e POINTER_QT_PYTHON=/qt/bin/python -e POINTER_ELECTRON_TODO=/repo/infra/bench/desktop/apps/electron-todo` to the test command. With `-e POINTER_DESKTOP_TREE_REPORT=/repo/infra/bench/desktop/results/tree` every tree test writes its coverage (controls found, elements listed, time of one `desktop_tree`, click by id) as JSON into that git-ignored folder; run them with `tests::tree -- --test-threads 1` for the timings in [desktop.md](../../../docs/benchmarks/desktop.md).

## Measure

```sh
infra/bench/desktop/measure.sh pointer-runner:gui
```

## Live check with real models

Build the bench device inside the image once; it then reports the `desktop` capability because `Xvfb` and `xdotool` are on its path:

```sh
docker run --rm -v "$PWD:/repo" -v pointer-bench-device-target:/target -w /repo/infra/bench/device   --entrypoint cargo pointer-runner:gui build --release --target-dir /target
```

With the backend running and the benchmark account loaded (`eval "$(python infra/bench/account.py env)"`, see [docs/benchmarks/README.md](../../../docs/benchmarks/README.md)):

```sh
python infra/bench/desktop/live_check.py --model "Experiential Labs::gpt-6-luna" --schema-cost
```

`--task notes` (phase 1.5) asks for two notes, the Pinned box and a cancelled Export dialog in `gtk-notes` without mentioning the tree, and records how many `desktop_tree` calls and clicks by `elementId` the model made. `--model` takes a key or `provider::key` and can be repeated; `--coordinates normalized` switches the models to the 0–1000 grid first. `--task clicks` replaces the message-order task with six separate clicks, one screenshot each, for the image masking runs of phase 1.3: restart the backend with `POINTER_DESKTOP_KEEP_IMAGES` and `POINTER_DESKTOP_IMAGE_MASK_BLOCK` set to the policy under test and pass it as `--label`; `--runs` repeats the task per model. The script turns vision on for the models during the run, restores their settings afterwards and saves the runs in the git-ignored `results/`. The task passes when the application's state file shows two increments and the typed text. Free plans limit requests per minute: a model that needs about ten requests per task can hit a limit of ten per minute, so run such models one at a time.

## Sandbox of a local session (stage 2)

Build the bench device for Windows (`cargo build --release` in `infra/bench/device`), start the backend with `POINTER_CONTAINER_ENABLED=true` and the `pointer-runner:gui` image available, load an account and run:

```sh
python infra/bench/desktop/sandbox_check.py --model "TokenHarbor::deepseek-v4.1-flash:free"
```

The script turns the sandbox on for the project `sandbox-check`, turns vision on for the model during the run and deletes each session afterwards, which also removes its sandbox; `--keep` keeps them for inspection.

`--task sync` (phase 2.2) writes a small project into the workspace (`check.py`, `version.txt`, a `.gitignore` that ignores `build/`, a `.env` and a file in `build/`) and asks the agent to send it with `sandbox_sync`, run `python3 check.py` in the sandbox, change `version.txt` on the local machine and sync and run again. The run passes when both syncs succeed, the two outputs show the old and the new version with neither `.env` nor `build/` in the sandbox, and a listing of the sandbox's project folder taken with `docker exec` after the turn confirms it. Use a workspace outside this repository (`--workspace`): the repository's own `.gitignore` applies to a folder inside it and already ignores `.env`, so the secret filter would have nothing to do. `--task measure` only syncs twice and counts the files in the sandbox, for timing a real project: point `--workspace` at it (the agent is told to change nothing).

### What a pass proves (metric 10)

Copy the fixture to a folder outside this repository, build it natively on Windows once and run both sides:

```sh
python infra/bench/desktop/transfer_check.py --side windows --prepare --workspace <folder>   # after cargo build --release in <folder>
python infra/bench/desktop/transfer_check.py --side linux --workspace <folder> --model "TokenHarbor::deepseek-v4.1-flash:free"
```

`--prepare` copies the fixture and writes a `.gitignore` that ignores `target/` and `gen/`, so the Windows build output is not synced. The Linux side uses one session for all tasks: a first turn syncs the project and runs `cargo build --release` in the sandbox, then every task is a turn of its own, with the application stopped and the state folder cleared by `docker exec` in between. The Windows side opens the application window on the desktop for a few seconds per task. Results go to `results/transfer-<side>-<time>.json`.

## Benchmark (phase 1.6)

Build `tauri-counter` into a volume once, next to the Qt and Electron volumes above:

```sh
docker run --rm -v "$PWD:/repo" -v pointer-tauri-target:/target -w /repo/infra/bench/desktop/apps/tauri-counter   --entrypoint cargo pointer-runner:gui build --release --target-dir /target
```

Check that every task can be solved, and measure the settle times and the resources of each fixture:

```sh
python infra/bench/desktop/run.py --verify-fixture --settle --tasks all,grid-1280x800-1
```

With the backend running and the benchmark account loaded (`eval "$(python infra/bench/account.py env)"`):

```sh
python infra/bench/desktop/run.py --model "TokenHarbor::deepseek-v4.1-flash:free" --tasks matrix --runs 3 --label matrix --retries 0 --resumes 3
python infra/bench/desktop/run.py --model "TokenHarbor::deepseek-v4.1-flash:free" --tasks grid-1280x800-1,griddense-1280x800-1 --coordinates normalized --label grid
python infra/bench/desktop/run.py --model "TokenHarbor::deepseek-v4.1-flash:free" --tasks tree --runs 2 --label tree-on
python infra/bench/desktop/run.py --model "TokenHarbor::deepseek-v4.1-flash:free" --tasks tree --runs 2 --no-tree --label tree-off
python infra/bench/desktop/run.py --model "Experiential Labs::gpt-6-luna" --calibrate
python infra/bench/desktop/report.py success --label matrix
```

`report.py` prints one section at a time: `success`, `cost` and `failures` (filter with `--label`), `grounding` (`--label grid`), `tree` (`--label tree-on --label tree-off`), `masking` (with the `keep-…` labels it adds a per-policy summary per request), `settle` (all reference runs with settle data) and `calibration`.

`--tasks` takes task ids and the groups `matrix` (15 tasks), `tree` (one sequence per toolkit), `masking` and `all`. `--no-tree` disables the `desktop_tree` row in the local database for the runs and enables it again afterwards. For the masking ablation restart the backend with `POINTER_DESKTOP_KEEP_IMAGES` and `POINTER_DESKTOP_IMAGE_MASK_BLOCK` set and pass the policy as `--label`. The device container mounts the copied fixtures at `/workspace`, the Electron modules, the Qt environment and the Tauri build, and is recreated for every run, so each run starts on an empty display. Free plans limit requests per minute; `--pause` waits between runs and `--retries` reruns a run that ended in a provider error.
