# F7. Container and display

Part of [F7](README.md). Covers GUI-1, GUI-2, GUI-9.

## Image

`infra/runner/Dockerfile` has the targets `slim` (git, Python, ripgrep) and `full` (adds the C toolchain, Go, Node.js, JDK 21, Rust). A new target `gui` extends `full`:

```
FROM full AS gui
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb openbox xdotool scrot x11-utils dbus-x11 at-spi2-core \
        fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji \
        chromium libgtk-3-0 libwebkit2gtk-4.1-0 libxss1 libnss3 libasound2t64 \
    && rm -rf /var/lib/apt/lists/*
COPY infra/runner/pointer-chromium /usr/local/bin/pointer-chromium
USER pointer
ENV DISPLAY=:99 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GDK_BACKEND=x11 \
    QT_QPA_PLATFORM=xcb
```

Package names are indicative; the exact list is fixed in phase 1.1 by building the fixture applications. What each part is for:

| Part | Purpose |
|---|---|
| `xvfb` | Virtual framebuffer: an X server that renders into memory and needs no GPU or physical display. |
| `openbox` | Minimal window manager, so windows get title bars, focus and sensible geometry. Without one, many applications misplace dialogs and focus. |
| `xdotool` | Pointer and keyboard input into the X server. |
| `scrot` | Screenshot of the whole display. |
| `x11-utils` | `xdpyinfo` to know when the display is ready, `xwininfo` for diagnostics. |
| `dbus-x11`, `at-spi2-core` | Session bus and the accessibility bus used by phase 1.5. |
| Fonts | Screenshots with missing glyphs mislead the model, so a broad font set is part of the image. |
| `chromium` and `pointer-chromium` | A browser for web applications. The wrapper adds `--no-sandbox` (the container is the sandbox and has no user namespaces), `--disable-dev-shm-usage`, `--disable-gpu` and `--force-renderer-accessibility`, and a fixed window size. |
| GTK, WebKitGTK and related libraries | Runtime for Tauri and GTK applications. Development headers are installed by the agent when a build needs them. |

`LIBGL_ALWAYS_SOFTWARE=1` makes Mesa render in software. WebGL and heavy animation run slowly; this is a stated limit, not a bug to fix.

The image size grows by an estimated few hundred megabytes over `full`. Phase 1.1 measures it.

## Display lifecycle

The runner owns the display. Nothing starts at container start, so a session that never uses a GUI pays nothing.

1. `desktop_start` (idempotent) runs `Xvfb :99 -screen 0 <width>x<height>x24 -nolisten tcp`, waits until `xdpyinfo` succeeds (at most 10 seconds), starts a D-Bus session and `openbox`, and records the state. Width and height default to 1280x800 and are bounded to 800x600 up to 1920x1200. A second call with other dimensions fails with `display_running` instead of resizing.
2. The display stays up until the container stops. There is no `desktop_stop` for the display, only for processes: restarting the display would kill every window and the agent has no use for it.
3. A container stopped for idleness ([ADR 0016](../../adr/0016-remote-execution-in-containers.md)) loses the display and the processes. The next `desktop_*` call on a fresh container fails with `display_not_started`; the agent starts it again. The checkout survives on the volume, the running application does not.

`-nolisten tcp` keeps the X server reachable only through its Unix socket inside the container. No VNC or other server is started, so the container exposes no new port.

## Processes

`run_command` waits for its command to exit and kills the process group on timeout, so it cannot keep a GUI application alive. `desktop_launch` exists for that.

- `desktop_launch { command, workingDirectory? }` starts the command through `bash -c` in its own session and process group with `DISPLAY` set, redirects its output to `/tmp/pointer-desktop/<processId>.log`, and returns `{ processId, pid, logPath }` once the process has run for a short grace period (default 1 second) or exited. A process that exits within the grace period returns `exited: true` with its exit code and the tail of the log, which is the usual way to learn that a command was wrong.
- `desktop_stop { processId }` signals the process group, waits briefly, then kills it. Unknown ids fail with `process_not_found`.
- At most 8 live processes per session; more fail with `too_many_processes`.
- The agent reads the log with `read_file` or `run_command`, so no log tool is needed.

The runner process keeps `DISPLAY=:99` in its own environment from the start. After `desktop_start`, anything started with `run_command` that opens a window (a test runner that spawns a browser, for example) also appears on the display, but it is killed with the command, which is why `desktop_launch` is the documented way for applications the agent wants to look at.

## Resources and limits

| Limit | Value | Where |
|---|---|---|
| Display size | 800x600 to 1920x1200, default 1280x800 | `pointer.desktop.default-width`, `default-height` |
| Shared memory | 512 MB, needed by browsers | container spec `shmSize` (new field, set for every runner container) |
| Container memory | measured in phase 1.1; an idle display with a browser is expected to need several hundred megabytes more than a runner without one | `pointer.container.memory` |
| Live processes | 8 | `pointer.desktop.max-processes` |
| Screenshot size | 2 MB encoded, PNG first, JPEG quality 80 when PNG is larger | `pointer.desktop.max-image-bytes` |

The production host is shared, so GUI sessions count against `pointer.container.max-running` like any other and the memory figure above decides how many fit. If the measurements show a GUI session needs much more than a plain one, a separate `pointer.container.gui-memory` is added and applied when the first `desktop_start` is expected; Docker cannot raise the limit of a running container without an update call, which the `container` module would have to make. This is decided with the numbers in phase 1.1.

## Isolation

- The container runs as the non-root user `pointer` with no extra capabilities, as before. Xvfb, openbox, xdotool and scrot need none. Chromium's own sandbox needs user namespaces, which a container without extra capabilities does not have, hence `--no-sandbox` in the wrapper; the container boundary replaces it.
- The application under test runs with the same rights as every other command the agent runs there. The threat model of [ADR 0016](../../adr/0016-remote-execution-in-containers.md) is unchanged; what is new is that the agent can browse arbitrary pages and read their content, which is covered in [agent-and-context.md](agent-and-context.md#security).
- The bridge network with outbound access stays, so the agent can install dependencies and the application can reach cloud services.

## What the container cannot do

| Limit | Consequence |
|---|---|
| Linux only | WPF, Win32, WebView2, macOS applications cannot run. Cross-platform applications run in their Linux build. |
| No GPU | Software rendering. WebGL, video and games are slow or unusable. |
| No audio, camera, USB | Applications that need them fail at startup or run degraded. |
| Rendering differs from the user's platform | Fonts, scrollbars, native dialogs, and for Tauri the web engine (WebKitGTK instead of WebView2). Tests prove logic and layout, not pixel identity with Windows. |
| No Docker in the container | Services the application needs (a database, a broker) are installed and started inside the container, or reached over the network. Nothing on the user's computer is reachable. |
| Linux file dialogs and clipboard | Native GTK or Qt dialogs work but look and behave as on Linux; the agent should drive the application by its own means where it can. |
| No Wayland | X11 only. Toolkits are forced to X11 through `GDK_BACKEND` and `QT_QPA_PLATFORM`. |
