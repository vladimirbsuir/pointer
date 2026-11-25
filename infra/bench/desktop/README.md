# Desktop sandbox harness

Fixtures and scripts for [F7](../../../docs/features/f7-gui-sandbox/README.md). Results are in [docs/benchmarks/desktop.md](../../../docs/benchmarks/desktop.md).

| Path | Purpose |
|---|---|
| `apps/tk-counter/app.py` | Tk window with a button, an entry, a list and `ctrl+s`; writes its state and the screen bounds of its widgets to the JSON file given as the first argument and prints `tk-counter ready` once mapped. Tk has no accessibility tree, so it is also the pixels-only case. |
| `apps/form-web/index.html` | Page for Chromium with a list reordered by HTML5 drag and drop and a text field; the page title carries `{"order", "text"}`, so a test reads the result from the window title. Items and the field have distinct colours for locating them in a screenshot. |
| `apps/webkit-counter/` | A `wry` window (the webview layer of Tauri) on WebKitGTK with a counter button; the title shows the count. Stands in for `tauri-counter` until phase 1.6. |
| `apps/gtk-notes/app.py` | GTK 3 window with a File menu (New note, Export… with a file chooser), a note entry, an Add note button, a Pinned check box and a list; writes its state to the JSON file given as the first argument and prints `gtk-notes ready`. The main accessibility tree fixture. |
| `apps/qt-settings/app.py` | Qt 6 (PySide 6 from `pip`) window with two tabs, a Dark mode check box, a Font size spin box, a Proxy field and an Apply button; writes its state like `gtk-notes` and prints `qt-settings ready`. |
| `apps/electron-todo/` | Electron 39 window with a todo field, an Add button, a Hide done check box and a list; the title carries `{"todos", "hideDone"}` and the log `electron-todo ready`. Its web content has an accessibility tree only with `--force-renderer-accessibility`. |
| `apps/swing-calc/SwingCalc.java` | Java Swing window with two number fields, a Plus button and a result label, run as a single source file (`java SwingCalc.java <state file>`); writes its state and prints `swing-calc ready`. |
| `live_check.py` | Live check with real models: runs the bench device inside the `gui` image and gives the agent a task on `tk-counter`, or with `--task notes` on `gtk-notes`, where the accessibility tree is available (phase 1.5). The default task (phase 1.2) sends a second message while the turn runs and records whether the provider accepted the screenshot message after the tool results and two user messages in a row; `--task clicks` (phase 1.3) takes a screenshot per click for the image masking comparison; `--schema-cost` compares the prompt tokens of a one-step turn with and without the desktop tools. |
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
