# GUI testing sandbox

Measurements for [F7](../features/f7-gui-sandbox/README.md). Phase 1.1 (resources of the display stack) is below; the benchmark of phase 1.6 (success, grounding, cost and masking per model) will be added here.

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

- **The default image stays `full`.** `gui` is offered as `--runner gui` in `init-env.sh` and as the `gui` tag of the image. The desktop tools are not offered to the model before phase 1.2, and the extra 1.2 GB per host is not worth it on the shared production host until then; phase 1.2 counts the schema cost and revisits the default.
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
