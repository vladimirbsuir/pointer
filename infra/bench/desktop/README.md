# Desktop sandbox harness

Fixtures and scripts for [F7](../../../docs/features/f7-gui-sandbox/README.md). Results are in [docs/benchmarks/desktop.md](../../../docs/benchmarks/desktop.md).

| Path | Purpose |
|---|---|
| `apps/tk-counter/app.py` | Tk window with a button, an entry, a list and `ctrl+s`; writes its state and the screen bounds of its widgets to the JSON file given as the first argument and prints `tk-counter ready` once mapped. Tk has no accessibility tree, so it is also the pixels-only case. |
| `apps/form-web/index.html` | Page for Chromium with a list reordered by HTML5 drag and drop and a text field; the page title carries `{"order", "text"}`, so a test reads the result from the window title. Items and the field have distinct colours for locating them in a screenshot. |
| `apps/webkit-counter/` | A `wry` window (the webview layer of Tauri) on WebKitGTK with a counter button; the title shows the count. Stands in for `tauri-counter` until phase 1.6. |
| `live_check.py` | Live check with real models: runs the bench device inside the `gui` image and gives the agent a task on `tk-counter`. The default task (phase 1.2) sends a second message while the turn runs and records whether the provider accepted the screenshot message after the tool results and two user messages in a row; `--task clicks` (phase 1.3) takes a screenshot per click for the image masking comparison; `--schema-cost` compares the prompt tokens of a one-step turn with and without the desktop tools. |
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

Without `POINTER_DESKTOP_TESTS` the display tests return early, so `cargo test` on a developer machine skips them; without `POINTER_WEBKIT_COUNTER` the WebKit test is skipped.

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

`--model` takes a key or `provider::key` and can be repeated; `--coordinates normalized` switches the models to the 0–1000 grid first. `--task clicks` replaces the message-order task with six separate clicks, one screenshot each, for the image masking runs of phase 1.3: restart the backend with `POINTER_DESKTOP_KEEP_IMAGES` and `POINTER_DESKTOP_IMAGE_MASK_BLOCK` set to the policy under test and pass it as `--label`; `--runs` repeats the task per model. The script turns vision on for the models during the run, restores their settings afterwards and saves the runs in the git-ignored `results/`. The task passes when the application's state file shows two increments and the typed text. Free plans limit requests per minute: a model that needs about ten requests per task can hit a limit of ten per minute, so run such models one at a time.
