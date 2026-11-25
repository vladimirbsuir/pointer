# F7. Container and display

Part of [F7](README.md). Covers GUI-1, GUI-2, GUI-9, GUI-13.

## Image

`infra/runner/Dockerfile` has the targets `slim` (git, Python, ripgrep) and `full` (adds the C toolchain, Go, Node.js, JDK 21, Rust). Phase 1.1 changes `slim` for root ([Privileges](#privileges-gui-13)) and adds a target `gui` that extends `full`.

`slim` gains `sudo`, a system-wide `safe.directory` and no longer switches to `pointer`:

```
FROM debian:trixie-slim AS slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git python3 ripgrep sudo \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --uid 1000 pointer \
    && mkdir -p /workspace \
    && chown pointer:pointer /workspace \
    && git config --system --add safe.directory '*'
COPY --from=build /src/target/release/pointer-runner /usr/local/bin/pointer-runner
WORKDIR /workspace
ENTRYPOINT ["pointer-runner"]
```

`full` keeps installing Rust as `pointer` into `/home/pointer` and ends with `USER root`. The new `gui` target:

```
FROM full AS gui
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb openbox xdotool scrot xclip x11-utils dbus-x11 at-spi2-core libatk-adaptor x11vnc websockify \
        fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji \
        chromium libgtk-3-0 libwebkit2gtk-4.1-0 libxss1 libnss3 libasound2t64 \
        libxcb-cursor0 libxkbcommon-x11-0 python3-tk openjdk-21-jre libatk-wrapper-java libatk-wrapper-java-jni \
        libgtk-3-dev libwebkit2gtk-4.1-dev libssl-dev librsvg2-dev libayatana-appindicator3-dev libxdo-dev \
    && rm -rf /var/lib/apt/lists/*
COPY infra/runner/pointer-chromium /usr/local/bin/pointer-chromium
ENV DISPLAY=:99 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GDK_BACKEND=x11 \
    QT_QPA_PLATFORM=xcb \
    QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1 \
    ACCESSIBILITY_ENABLED=1 \
    WEBKIT_DISABLE_DMABUF_RENDERER=1 \
    WEBKIT_DISABLE_COMPOSITING_MODE=1 \
    WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1 \
    ELECTRON_DISABLE_SANDBOX=1
```

Package names and variables are indicative; the exact list is fixed in phase 1.1 by building and starting the fixture applications. As built in phase 1.1 the list also has `xauth` and `dbus`, uses `libgtk-3-0t64`, and the image sets `LANG=C.UTF-8`, which `xdotool type` needs for non-ASCII text. The wrapper `pointer-chromium` is written by the Dockerfile itself, because the build context is `desktop/src-tauri`. What each part is for:

| Part | Purpose |
|---|---|
| `xvfb` | Virtual framebuffer: an X server that renders into memory and needs no GPU or physical display. |
| `openbox` | Minimal window manager, so windows get title bars, focus and sensible geometry. Without one, many applications misplace dialogs and focus. |
| `xdotool` | Pointer and keyboard input into the X server, and the window search behind `desktop_wait` with `untilWindow`. |
| `scrot` | Screenshot of the whole display. |
| `xclip` | Clipboard access, the fallback of `desktop_type` for characters `xdotool type` cannot produce. |
| `x11-utils` | `xdpyinfo` to know when the display is ready, `xwininfo` for diagnostics. |
| `dbus-x11`, `at-spi2-core`, `libatk-adaptor` | Session bus, the accessibility bus, and the bridge that makes GTK 3 applications export their tree (phase 1.5). |
| `x11vnc`, `websockify` | The live view of phase 1.4: `x11vnc` serves the display as VNC, `websockify` exposes it as a WebSocket for the noVNC client. The noVNC library itself is bundled in the desktop client, not in the image. |
| Fonts | Screenshots with missing glyphs mislead the model, so a broad font set is part of the image. |
| `chromium` and `pointer-chromium` | A browser for web applications. The wrapper adds `--no-sandbox` (Chromium refuses to run as root otherwise, and its sandbox needs user namespaces the container does not have), `--disable-dev-shm-usage`, `--disable-gpu` and `--force-renderer-accessibility`, and a fixed window size. |
| GTK, WebKitGTK and related libraries | Runtime for Tauri and GTK applications. |
| Tauri build prerequisites (`libwebkit2gtk-4.1-dev`, `libgtk-3-dev`, `libssl-dev`, `librsvg2-dev`, `libayatana-appindicator3-dev`, `libxdo-dev`) | So that `tauri build` and `tauri dev` work without an install step. Other development packages the agent installs itself with `apt-get`, as root. |
| `libxcb-cursor0`, `libxkbcommon-x11-0` | Libraries the Qt 6 `xcb` platform plugin loads; the Qt bindings themselves come from `pip`. |
| `python3-tk` | Tk for the `tk-counter` fixture and Python GUI scripts. |
| `openjdk-21-jre` | The full Java runtime with AWT and X11 support; `full` has only the headless JDK, in which Swing and JavaFX cannot open a window. |
| `libatk-wrapper-java`, `libatk-wrapper-java-jni` | The Java ATK wrapper that exposes Swing to AT-SPI on Linux. It is enabled through the JDK's `accessibility.properties` (`assistive_technologies=org.GNOME.Accessibility.AtkWrapper`) if the package does not do so itself, not through `JAVA_TOOL_OPTIONS`, which would print a notice on every `java` start. Checked in phase 1.5. |

Environment variables:

| Variable | Purpose |
|---|---|
| `DISPLAY=:99` | Every process in the container finds the display, including those started by `run_command`. |
| `LIBGL_ALWAYS_SOFTWARE=1` | Mesa renders in software. WebGL and heavy animation run slowly; this is a stated limit, not a bug to fix. |
| `GDK_BACKEND=x11`, `QT_QPA_PLATFORM=xcb` | Force GTK and Qt to X11; there is no Wayland. |
| `WEBKIT_DISABLE_DMABUF_RENDERER=1`, `WEBKIT_DISABLE_COMPOSITING_MODE=1` | WebKitGTK, and therefore Tauri, shows a blank window when its DMA-BUF and accelerated compositing paths have no GPU behind them. Phase 1.1 found that WebKitGTK 2.50 of Debian trixie renders under Xvfb without either; both stay as a safeguard ([desktop.md](../../benchmarks/desktop.md)). |
| `WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1`, `ELECTRON_DISABLE_SANDBOX=1` | WebKitGTK's bubblewrap sandbox and Electron's Chromium sandbox need user namespaces and refuse root; the container is the sandbox. |
| `QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1`, `ACCESSIBILITY_ENABLED=1` | Qt and Chromium-based applications (Electron, Chromium) export their accessibility tree only when they detect an assistive technology; these force it. Confirmed per toolkit in phase 1.5. |

The image size grows by 1.24 GB unpacked over `full` (3.24 GB in total, 1.18 GB compressed), mostly Chromium, WebKitGTK and the Java runtime ([desktop.md](../../benchmarks/desktop.md)).

## Privileges (GUI-13)

Today the container runs as `pointer` with every capability dropped and `no-new-privileges`, so the agent cannot install a system package, start a database from the distribution or add a development library a build needs. The container is the sandbox, so stage 1 gives the agent root inside it.

### Root with a reduced capability set

- The runner and every command it starts run as root. `DockerContainerRuntime` keeps `CapDrop: ALL` and `no-new-privileges` and adds back only what package installation and ordinary process management need:

  | Capability | Needed for |
  |---|---|
  | `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `FSETID` | `dpkg` and `apt-get` writing and owning files of every package |
  | `SETUID`, `SETGID` | `apt-get` dropping to its `_apt` user for downloads; `runuser` and `sudo` switching users |
  | `KILL` | Signalling processes that run as another user, such as a database started as `pointer` |
  | `NET_BIND_SERVICE` | Development servers that listen on ports below 1024 |

  Everything else stays dropped: `NET_RAW`, `MKNOD`, `SYS_CHROOT`, `SETFCAP`, `SETPCAP`, `AUDIT_WRITE` and every administrative capability. `no-new-privileges` stays: root needs no set-user-id binary, and `sudo` run by root works without gaining anything.
- `sudo` is installed so that scripts and build instructions that call it work unchanged.
- The `pointer` user stays for programs that refuse to run as root, such as PostgreSQL's `initdb` and `postgres`; the agent runs them with `runuser -u pointer --`. The tool descriptions do not need to mention it; the error messages of those programs say so.
- `git config --system --add safe.directory '*'` stops git from refusing the checkout, whose existing files belong to uid 1000 in volumes created before this change. No ownership change of existing volumes is needed: root reads and writes them.
- The browser and web engine sandboxes are off ([Image](#image)); the container boundary replaces them, as before.

### The repository token stays outside

Root reads every file and every process environment in the container, so a secret inside it is a secret the agent, and an injected instruction, can read. Today the user's GitHub token, with the `repo` scope over all their repositories, enters the container in the arguments of `pointer.repo_prepare` and `pointer.repo_publish` and is passed to git as `GIT_CONFIG_VALUE_0`. That is already weaker than [ADR 0016](../../adr/0016-remote-execution-in-containers.md) states: git hooks inherit the environment, so a `pre-push` hook written by the agent reads the token on the next push, and the runner's `PATH` starts with the writable `/home/pointer/.cargo/bin`, where a planted `git` would receive it. With root the protection disappears by construction.

So the token leaves the container before root arrives, in the same phase:

- The backend gets a **repository proxy** for git's smart HTTP protocol ([tools-and-protocol.md](tools-and-protocol.md#repository-proxy-phase-11)). The runner clones from and pushes to `${POINTER_RUNNER_API_URL}/api/v1/runner/git/<owner>/<name>.git`, authenticated with its own short-lived access token as an `Authorization` header, as it already authenticates to the gateway.
- The proxy checks that the token belongs to the runner device of a session whose project has this repository, streams the request to GitHub with the user's token added, and streams the answer back. Reads (`git-upload-pack`) pass through. Writes (`git-receive-pack`) are accepted only when every ref update in the command list targets `refs/heads/<session branch>` and none is a deletion.
- `pointer.repo_prepare` and `pointer.repo_publish` no longer receive `token`; `cloneUrl` becomes the proxy URL, so `origin` in the checkout points at the proxy and `git fetch` by the agent works within the same rules.
- What an injected instruction can do with the runner's credentials (the runner secret in the environment, the access tokens it exchanges for) is what it could already do: read the repository it was cloned from and push to the session branch.

Creating the pull request already happens on the backend with the user's token and is unchanged.

### Outdated containers

Containers created before a phase lack its changes: root and capabilities in phase 1.1, the `gui` image when it becomes the default, the view passwords in phase 1.4. The `container` module labels each container with a hash of its spec (`dev.pointer.spec`: image name, user, capabilities, security options, init, shared memory, process limit, memory, CPUs, network, published ports and the names of its environment values). When a container is started and its label differs from the current spec, or the container was created from another image ID than the configured image name now resolves to (a rebuilt `latest` with a new runner), it is removed and created again on the same volume. The checkout survives; installed packages and running processes do not, as after any container restart.

Implemented in phase 1.1 together with two settings the display needs in every container: `Init: true`, so Docker's init is PID 1 and reaps processes orphaned by GUI applications and killed process groups, and `ShmSize` from `pointer.container.shm-size`.

## Display lifecycle

The runner owns the display. Nothing starts at container start, so a session that never uses a GUI pays nothing.

1. `desktop_start` runs `Xvfb :99 -screen 0 <width>x<height>x24 -nolisten tcp`, waits until `xdpyinfo` succeeds (at most 10 seconds), starts a D-Bus session bus and `openbox`, waits until openbox has published `_NET_CLIENT_LIST` (its end of start-up; a window opened earlier stays unmapped), and records the state. Stale X lock files of a display whose server is gone, left by a stopped container, are removed first. Width and height default to 1280x800 and are bounded to 800x600 up to 1920x1200.
2. The call is idempotent. When the display is already running, a call without dimensions or with the current ones returns the current state; a call with other dimensions fails with `display_running` instead of resizing.
3. After start the runner passes `DISPLAY` and `DBUS_SESSION_BUS_ADDRESS` to every process it starts afterwards, by `desktop_launch` or by `run_command`, so they find the display, the session bus and through it the accessibility bus.
4. The display stays up until the container stops. There is no `desktop_stop` for the display, only for processes: restarting the display would kill every window and the agent has no use for it.
5. A container stopped for idleness ([ADR 0016](../../adr/0016-remote-execution-in-containers.md)) loses the display and the processes. The next `desktop_*` call on a fresh container fails with `display_not_started`; the agent starts it again. The checkout survives on the volume, the running application does not.

`-nolisten tcp` keeps the X server reachable only through its Unix socket inside the container. The only listening port the display adds is the live view port described below.

## Live view stack (phase 1.4)

When the display is up, the runner also starts the stream:

1. `x11vnc -display :99 -rfbport 5900 -localhost -forever -shared -viewonly -passwdfile <file>`: VNC on the loopback of the container only, view-only for every client. The password file has a random full password on its first line and, after the `__BEGIN_VIEWONLY__` marker, the view password, the only one the client ever receives. The runner writes the file with mode 600 from the environment values `POINTER_VNC_PASSWORD` and `POINTER_VNC_VIEW_PASSWORD`, so neither appears on a command line. A viewer who learned the view password still cannot send input, with or without `-viewonly`. The marker syntax is confirmed against the installed `x11vnc` in phase 1.1.
2. `websockify 6080 localhost:5900`: the WebSocket endpoint noVNC connects to. This is the one port the container listens on besides the runner's outbound connection.

VNC authentication is DES-based and uses only the first 8 bytes of a password, so both passwords are exactly 8 random letters and digits (about 48 bits); longer ones would only look stronger, and leaving out punctuation and spaces avoids the comment and keyword lines of the password file. That is enough for the password's role here, keeping neighbouring containers out of a port the backend reaches anyway: guessing 52 bits over the network, one RFB handshake per attempt, is impractical. The RFB traffic between the container and the backend is unencrypted and stays inside the Docker network or on the host's loopback; the leg from the backend to the client is TLS through the reverse proxy.

The backend creates both passwords when it creates the container and passes them in the environment. It stores the view password encrypted with the cipher used for provider keys, and does not store the full one ([tools-and-protocol.md](tools-and-protocol.md#live-view-phase-14)). The agent can read both from the environment as root; that gives it nothing it does not have, since it already controls the display.

Reaching port 6080 from the backend depends on the deployment and is set by `pointer.container.desktop-access`:

| Value | How | When |
|---|---|---|
| `network` | The backend resolves the container by name on the runner network (`pointer-runners`, a user-defined network, where Docker's name resolution works) | The compose deployment, where the backend is on that network |
| `published` | The container publishes 6080 on an ephemeral port of `127.0.0.1` of the Docker host and the backend connects there | Development with the backend on the host, where container addresses are not routable |

The port is never published on a public interface. On the shared `pointer-runners` network a container can reach another session's port 6080; the per-session view password is what stops it from watching, which is why the stream is not left unauthenticated even though it is view-only.

The `container` module gains the container's stream address (`ContainerRuntime.streamAddress`), the published port mapping for `published`, and the two environment values. If phase 1.4 finds either access mode unworkable on a real host, the reverse tunnel of the [README](README.md) (key decision 6) replaces both.

As implemented in phase 1.4: the stream module of `pointer-executor` (`desktop/stream.rs`) starts both processes after openbox only when both environment values are set and both programs are on the path, waits up to 10 seconds for both ports and otherwise leaves the display running without a stream; `pointer.desktop_status` reports `stream`. The password file `/tmp/pointer-desktop/vnc.passwd` is created with mode 600 and the marker syntax works as documented with `x11vnc` 0.9.17: the full and the view password authenticate, both stay view-only, a wrong one is refused. `websockify` forks a process per viewer. `pointer.container.desktop-access` defaults to `published` and the compose file sets `network`; the published binding is part of the spec hash, as are the two new environment names, so every container created before phase 1.4 is recreated once on its next start.

## Processes

`run_command` waits for its command to exit and kills the process group on timeout, so it cannot keep a GUI application alive. `desktop_launch` exists for that.

- `desktop_launch { command, workingDirectory? }` starts the command through `bash -c` in its own session and process group with `DISPLAY` and the session bus set, redirects its output to `/tmp/pointer-desktop/<processId>.log`, and returns `{ processId, pid, logPath }` once the process has run for a short grace period (default 1 second) or exited. A process that exits within the grace period returns `exited: true` with its exit code and the tail of the log, which is the usual way to learn that a command was wrong.
- `desktop_stop { processId }` signals the process group, waits briefly, then kills it. Unknown ids fail with `process_not_found`.
- At most 8 live processes per session; more fail with `too_many_processes`.
- The agent reads the log with `read_file` or `run_command`, so no log tool is needed, and waits for a line in it with `desktop_wait` and `untilLog`.
- A slow build is run first with `run_command`, and the built binary is launched afterwards. Launching `tauri dev` or `npm start` directly works too, followed by `desktop_wait` with `untilWindow`, which waits up to two minutes without taking screenshots on the way.

After `desktop_start`, anything started with `run_command` that opens a window (a test runner that spawns a browser, for example) also appears on the display, but it is killed with the command, which is why `desktop_launch` is the documented way for applications the agent wants to look at.

## Resources and limits

| Limit | Value | Where |
|---|---|---|
| Display size | 800x600 to 1920x1200, default 1280x800 | `pointer.desktop.default-width`, `default-height` |
| Image sent to the model | At most 1280x800, aspect ratio kept; larger displays are scaled down | `pointer.desktop.max-image-width`, `max-image-height` |
| Shared memory | 512 MB, needed by browsers | container spec `shmSize` (new field, set for every runner container) |
| Process count | `pointer.container.pids-limit`, 512, which counts threads as well as processes. Chromium next to a parallel `cargo build` peaked at 283, so the default stays | container spec `PidsLimit` |
| Container memory | unchanged: an idle display needs 26 MB and one with Chromium about 160 MB ([desktop.md](../../benchmarks/desktop.md)) | `pointer.container.memory` |
| Live processes | 8 | `pointer.desktop.max-processes` |
| Wait | 10 seconds for a plain wait, 120 seconds for a wait on a condition | `pointer.desktop.max-wait-ms`, `max-condition-wait-ms` |
| Viewers per session | 2 | `pointer.desktop.max-viewers` |
| Screenshot size | 2 MB encoded, PNG first, JPEG quality 80 when PNG is larger | `pointer.desktop.max-image-bytes` |

The production host is shared, so GUI sessions count against `pointer.container.max-running` like any other and the memory figure above decides how many fit. If the measurements show a GUI session needs much more than a plain one, a separate `pointer.container.gui-memory` is added and applied when the first `desktop_start` is expected; Docker cannot raise the limit of a running container without an update call, which the `container` module would have to make. This is decided with the numbers in phase 1.1.

## Isolation

- Commands run as root inside the container with the reduced capability set of [Privileges](#privileges-gui-13). Without user namespace remapping, root in the container is root on the host if a kernel vulnerability lets a process escape; the dropped capabilities, `no-new-privileges` and Docker's default seccomp profile narrow that surface. Remapping is a daemon-wide setting on the shared production host and is recorded as a later hardening step, not done in stage 1.
- Nothing in the container is a secret worth stealing: the GitHub token stays on the backend, and the runner secret and the VNC passwords give access only to this session's container, repository branch and stream.
- The application under test runs with the same rights as every other command the agent runs there. What is new is that the agent can browse arbitrary pages and read their content, which is covered in [agent-and-context.md](agent-and-context.md#security).
- The bridge network with outbound access stays, so the agent can install dependencies and the application can reach cloud services.
- The stream port is protected by the view password and reachable only by the backend in normal operation; nothing in it accepts input.

## What the container cannot do

| Limit | Consequence |
|---|---|
| Linux only | WPF, Win32, WebView2, macOS applications cannot run. Cross-platform applications run in their Linux build. |
| No GPU | Software rendering. WebGL, video and games are slow or unusable. |
| No audio, camera, USB | Applications that need them fail at startup or run degraded. |
| Rendering differs from the user's platform | Fonts, scrollbars, native dialogs, and for Tauri the web engine (WebKitGTK instead of WebView2). Tests prove logic and layout, not pixel identity with Windows. |
| No Docker in the container | Services the application needs (a database, a broker) are installed with `apt-get` and started inside the container, or reached over the network. Nothing on the user's computer is reachable. |
| Linux file dialogs and clipboard | Native GTK or Qt dialogs work but look and behave as on Linux; the agent should drive the application by its own means where it can. |
| No Wayland | X11 only. Toolkits are forced to X11 through `GDK_BACKEND` and `QT_QPA_PLATFORM`. |
