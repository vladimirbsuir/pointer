# Desktop sandbox harness

Fixtures and scripts for [F7](../../../docs/features/f7-gui-sandbox/README.md). Results are in [docs/benchmarks/desktop.md](../../../docs/benchmarks/desktop.md).

| Path | Purpose |
|---|---|
| `apps/tk-counter/app.py` | Tk window with a button, an entry, a list and `ctrl+s`; writes its state and the screen bounds of its widgets to the JSON file given as the first argument and prints `tk-counter ready` once mapped. Tk has no accessibility tree, so it is also the pixels-only case. |
| `apps/form-web/index.html` | Page for Chromium with a list reordered by HTML5 drag and drop and a text field; the page title carries `{"order", "text"}`, so a test reads the result from the window title. Items and the field have distinct colours for locating them in a screenshot. |
| `apps/webkit-counter/` | A `wry` window (the webview layer of Tauri) on WebKitGTK with a counter button; the title shows the count. Stands in for `tauri-counter` until phase 1.6. |
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
