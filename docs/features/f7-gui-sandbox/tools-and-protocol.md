# F7. Tools and protocol

Part of [F7](README.md). Covers GUI-2 to GUI-5, GUI-7, GUI-8 and the protocol side of GUI-13.

## Model-facing tools

Each tool has an executor operation of the same name, like `run_command`. All are offered in Agent mode only. `kind` is `read` for observation and `execute` for everything that changes state or starts processes; the default policy is `ask` as for other execute tools, and server sessions auto-approve it (PERM-6).

All coordinates and sizes in arguments and results are in the model's space ([Coordinate spaces](#coordinate-spaces)).

| Tool | Arguments | Result (text part) | Kind |
|---|---|---|---|
| `desktop_start` | `width?`, `height?` (display size in screen pixels) | `display`, `width` and `height` of the screenshots, `coordinates` (`pixels` or `normalized`), `windowManager` | execute |
| `desktop_launch` | `command`, `workingDirectory?` | `processId`, `pid`, `logPath`, `exited?`, `exitCode?`, `logTail?` | execute |
| `desktop_stop` | `processId` | `stopped` | execute |
| `desktop_screenshot` | `zoom?` (`x`, `y`, `width`, `height`) | `width`, `height`, `zoom?`, image | read |
| `desktop_move` | `x`, `y`, `observe?` | `x`, `y`, image | execute |
| `desktop_click` | `x`, `y` or `elementId`, `button?` (`left`, `middle`, `right`), `clicks?` (1 to 3), `modifiers?` (`ctrl`, `shift`, `alt`, `super`), `observe?` | `x`, `y`, image | execute |
| `desktop_drag` | `fromX`, `fromY`, `toX`, `toY`, `button?`, `observe?` | image | execute |
| `desktop_scroll` | `x`, `y`, `direction` (`up`, `down`, `left`, `right`), `amount?` (ticks, default 3), `observe?` | image | execute |
| `desktop_type` | `text`, `observe?` | `typed` (characters), `method` (`keys` or `paste`), image | execute |
| `desktop_key` | `keys` (xdotool key names joined by `+`, for example `ctrl+s`, `Return`), `observe?` | image | execute |
| `desktop_wait` | `ms?`, `untilWindow?` (part of a window title), `untilLog?` (`processId`, `text`), `observe?` | `met`, `waitedMs`, image | read |
| `desktop_tree` | `maxDepth?`, `maxNodes?`, `scope?` (`focused`, `all`) | `nodes` (phase 1.5) | read |

Rules:

- **Coordinates** have their origin at the top left of the screenshot. Values outside the screenshot fail with `invalid_coordinates`; nothing is clamped silently. On the `normalized` grid, 1000 addresses the last pixel of the axis.
- **`observe`** defaults to `true`. After an input operation the runner waits for the interface to settle and returns a screenshot, so the model does not need an extra call to see the effect. `observe: false` skips the capture for batched inputs, for example typing into several fields. The settle delay is `pointer.desktop.settle-ms` (default 300); it is a fixed wait, not change detection, and phase 1.6 checks whether that is good enough.
- **`desktop_screenshot` with `zoom`** captures that rectangle and scales it up to fit the image limit (at most fourfold), for reading small text. A zoomed image is for looking only: the coordinates of later calls still refer to the full screenshot, and the result repeats the rectangle so the model can relate the two. Zoomed images are masked like any other.
- **`desktop_drag`** presses the button at the start, moves to the end in ten intermediate steps over about 200 ms, and releases. Web pages need the intermediate moves before they start an HTML5 drag; a single jump is not recognised as one.
- **`desktop_type`** types through `xdotool type` with a small inter-key delay. Phase 1.1 tested it with Cyrillic, accented Latin and emoji: under a UTF-8 locale every character arrived exactly in Tk and in Chromium, so keys are the default. The executor operation keeps `method: paste` (the clipboard with `xclip`, then `ctrl+v`) for the backend to use if a toolkit turns out not to accept typed characters; the result reports the method used. Text over 2,000 characters fails with `text_too_long`; long input belongs in a file, not in a screen.
- **`desktop_key`** checks every name with `XStringToKeysym` before calling `xdotool`, because `xdotool` only prints a warning for an unknown name and still succeeds. An unknown name fails with `invalid_key`, listing none: the model should use the standard names.
- **`desktop_wait`** without a condition waits `ms` (required, at most 10,000). With `untilWindow` or `untilLog` it checks every 250 ms until the condition holds or `ms` has passed (default 60,000, at most 120,000), and takes the screenshot only at the end. `met: false` is a normal result, not an error. `untilWindow` matches visible windows by title through `xdotool search`, case-insensitively; `untilLog` looks for the text in the log of a process started by `desktop_launch`.
- **`desktop_click` with `elementId`** (phase 1.5) takes the id from the latest `desktop_tree` result, resolves it to the center of the element's bounds and clicks there. A stale id fails with `element_not_found`.
- **`desktop_tree`** (phase 1.5, as implemented) returns `scope`, `applications`, `nodes` (`id`, `depth`, `role`, `name`, `text?`, `value?`, `states?`, `x`, `y`, `width`, `height`), `truncated` and `note?`. `scope` defaults to `focused`, the window whose AT-SPI state is active, and falls back to `all` when no window is; `maxNodes` defaults to 150 (at most 500) and `maxDepth` to 30 (at most 60). Only showing elements inside the screen are listed, with clipped bounds; unnamed containers are skipped and their children move up, and labels that repeat their parent are dropped. An id names an element by its application, child-index path, role and name, and stays the same across trees while the display runs, so an id from an earlier tree still works while that element is there. A click by id checks role and name on the path again and fails with `element_not_found` when the element is gone, hidden or off the screen; giving both `elementId` and coordinates fails with `invalid_arguments`. The click result adds `elementId`, `role` and `name`. For the model the backend renders the tree as indented lines (`e5 button "Add note" [93, 171, 496, 34] (focused)`) with bounds in its coordinate space, and the item keeps the structured result. Other errors: `tree_unavailable` without the AT-SPI bindings, `tree_failed` when the reader does not answer in 25 seconds. See [ADR 0030](../../adr/0030-accessibility-tree.md).
- **Errors** common to all: `desktop_unavailable` (the executor has no display stack; the tools are not offered then, so this only happens on a race), `display_not_started`, `display_running`, `process_not_found`, `too_many_processes`, `invalid_coordinates`, `invalid_key`, `text_too_long`, `element_not_found`, `image_too_large`, and `needs_vision` (the call came from a model without vision and was not run; see [agent-and-context.md](agent-and-context.md#vision-gating)).

The tool descriptions carry the usage guidance (see [agent-and-context.md](agent-and-context.md#guidance-in-the-tool-descriptions)).

## Coordinate spaces

Vision models disagree on how they address a picture. Some answer in pixels of the image they were given; others, such as Qwen3-VL and Gemini, answer on a 0–1000 grid regardless of the image size. Providers also shrink large images before the model sees them, and a model can only answer in the image it saw. Three spaces are therefore kept apart:

| Space | Meaning | Who uses it |
|---|---|---|
| Screen | Pixels of the Xvfb display | The runner, `xdotool`, AT-SPI bounds |
| Image | Pixels of the screenshot as stored and sent to the model: the screen scaled by `s = min(1, maxImageWidth / width, maxImageHeight / height)` | Attachments, the client's thumbnails and markers |
| Model | Image pixels for a model with `coordinate_system = 'pixels'`, the 0–1000 grid on each axis for `'normalized'` | Tool arguments and results, tool descriptions |

With the defaults (display 1280x800, image limit 1280x800) `s` is 1, so for a model that answers in pixels all three spaces coincide. The image limit keeps screenshots at the size providers handle without resizing; a display larger than it is scaled down for the model.

- **`DesktopCoordinates`** in the backend converts every coordinate argument from model space to screen space before the `exec.request` (`image = n × imageSize / 1000` on the grid, then `screen = round(image / s)`), and every coordinate in the result, including the bounds in `desktop_tree`, back to model space before the result is stored as `resultText`. It reads the display and image sizes from the latest `desktop_start` or `pointer.desktop_status` result of the session.
- The runner works in screen space only. The backend adds `maxImageWidth` and `maxImageHeight` to the arguments of `desktop_start`, and the runner scales every capture to them; the model never sees these arguments.
- The model's `coordinate_system` is a column of `models` ([Data model](#data-model-vn__desktop_sandboxsql)), set in the model options next to vision ([desktop-ui.md](desktop-ui.md#settings)). It defaults to `pixels`; phase 1.6 measures each test model under both conventions and records which one it uses.
- The tool descriptions have one variant per convention, so the model is told the space it is expected to answer in ([agent-and-context.md](agent-and-context.md#guidance-in-the-tool-descriptions)).

## Executor operations and images in results

The operations are the tools above. `exec.request` is unchanged ([03-protocol.md](../../03-protocol.md) section 6.1); `timeoutMs` defaults to 30 seconds, `desktop_launch` counts the grace period inside it, and `desktop_wait` gets its wait plus 10 seconds.

One internal operation is added for the backend, never exposed to the model: `pointer.desktop_status` (no arguments) returns `{ running, width, height, imageWidth, imageHeight, processes }` and backs `GET /sessions/{sessionId}/desktop`. On a runner without the display stack it fails with `desktop_unavailable`.

`exec.result.result` of an operation that captures a screen gets one new field, with coordinates in screen space:

```json
{
  "x": 640,
  "y": 360,
  "images": [
    { "mediaType": "image/png", "width": 1280, "height": 800, "screenWidth": 1280, "screenHeight": 800, "data": "iVBORw0KGgo..." }
  ]
}
```

`data` is base64; `width` and `height` are the image's, `screenWidth` and `screenHeight` the display's (for a zoomed capture, the rectangle's). The backend does not keep `data` in the item: when `ExecutionService` receives a result with `images`, `DesktopImages` stores each one as an attachment (below), removes `images` from the result and stores a reference list on the item instead.

Limits: an image over `pointer.desktop.max-image-bytes` (default 2 MB) is never sent; the runner re-encodes it as JPEG and, if that is still too large, fails the operation with `image_too_large`. The gateway's `maxMessageBytes` (4 MB today) allows a 2 MB image plus its base64 overhead (about 2.7 MB) and the rest of the result; phase 1.2 checks it with the largest allowed image. `maxToolOutputBytes` applies to the text part only, so the runner applies it before adding `images`.

### `tool_call` item

Gets two new optional fields, present on desktop tool calls with a captured screenshot:

```json
"images": [ { "attachmentId": "0193a8c4-6f2e-7b1d-9a52-3c1e8f0d4b77", "mediaType": "image/png", "width": 1280, "height": 800, "zoom": false } ],
"marks": [ { "x": 640, "y": 360 } ]
```

`marks` are the points the call acted on (one for a click, a move or a scroll, two for a drag), in pixels of the attached image, computed by `DesktopCoordinates`, so the client can draw them without knowing the model's convention. Zoomed images have no marks.

`resultText`, which is what the model reads as the tool result, holds the text part in model space (coordinates, process id, and so on) and for calls with an image a one-line note, `Screenshot attached.`. The image itself reaches the model as described in [agent-and-context.md](agent-and-context.md#how-screenshots-enter-the-context). `GET /sessions/{id}/attachments/{attachmentId}` serves it with the existing authorization; the upload endpoint stays for user images only.

As implemented in phase 1.2: `DesktopCoordinates` does the arithmetic and `DesktopCalls` (package `agent.desktop`) does the rest of the work this section gives to `DesktopCoordinates` and `DesktopImages`: it injects the hidden arguments, converts coordinates both ways, validates and stores the images and computes `marks`. It keeps the display and image sizes of each session in memory, updated from every `desktop_start` result and every full screenshot, and asks `pointer.desktop_status` only when it has none (after a backend restart). On the grid the backend maps straight to the screen, `screen = round(n × (screenSize − 1) / 1000)`, which equals the two-step formula above and keeps 1000 on the last pixel for any scale. A screenshot that is not a PNG or JPEG of the declared size fails the call with `invalid_image`, one over the limit with `image_too_large`. `desktop_tree` is not offered before phase 1.5; migration V37 adds it.

## Capability and tool availability

- The `hello` message of a runner lists `capabilities: ["executor", "desktop"]` when its image has the display stack (`Xvfb` and `xdotool` found on the path), and `["executor"]` otherwise. `capabilities` gets the new value `desktop` in the `hello` table of [03-protocol.md](../../03-protocol.md) section 3.1. `GatewayHandler` today keeps only `executor` for a runner connection; it is changed to keep `executor` and `desktop` and still drops `viewer` and `relay`.
- Capabilities are persisted. Today a device's capabilities live only in its connection, so they are unknown while the runner is offline, which is the normal state of a server session between turns (the container stops after 30 idle minutes and starts on the first call of a turn). `devices.capabilities` stores the set of the latest `hello`; `ExecutionService` returns the live set when the device is connected and the stored one otherwise. A runner that has never connected uses `pointer.container.desktop`, which the operator sets to true when the configured image is the `gui` target. The tool list of a turn therefore does not change when the first call wakes the container.
- `ToolRegistry.available` drops tools whose operation needs `desktop` when the session's device lacks it, in the same way it drops custom tools whose interpreter is missing today.
- The agent loop applies vision gating on top ([agent-and-context.md](agent-and-context.md#vision-gating)).
- The desktop client ([ADR 0007](../../adr/0007-communication-model.md)) never reports `desktop` in stage 1, so local sessions never see these tools.

## Data model (`Vn__desktop_sandbox.sql`)

```sql
ALTER TABLE attachments
    ADD COLUMN origin text NOT NULL DEFAULT 'user' CHECK (origin IN ('user', 'desktop')),
    ADD COLUMN width integer,
    ADD COLUMN height integer;

ALTER TABLE devices
    ADD COLUMN capabilities text[] NOT NULL DEFAULT '{}';

ALTER TABLE models
    ADD COLUMN coordinate_system text NOT NULL DEFAULT 'pixels' CHECK (coordinate_system IN ('pixels', 'normalized'));
```

Screenshots reuse the session-scoped attachments table: they are removed with the session and served by the existing endpoint. `origin` keeps them apart from the images the user attaches, so a listing of the user's attachments and any retention rule can tell them apart. `width` and `height` are recorded for user uploads too from this migration on, so the token estimate can use them ([agent-and-context.md](agent-and-context.md#image-masking-phase-13)). The tool rows are inserted by the same migration with fixed ids in the style of V5, in the name order the context relies on ([ADR 0024](../../adr/0024-append-only-context.md)).

Update [04-data-model.md](../../04-data-model.md) in the same commit.

## Repository proxy (phase 1.1)

The runner reaches GitHub only through the backend, so the user's token never enters the container ([container.md](container.md#the-repository-token-stays-outside)).

| Endpoint | Description |
|---|---|
| `GET /api/v1/runner/git/{owner}/{name}.git/info/refs?service=git-upload-pack` or `git-receive-pack` | Reference advertisement, passed through. |
| `POST /api/v1/runner/git/{owner}/{name}.git/git-upload-pack` | Fetch and clone, passed through. |
| `POST /api/v1/runner/git/{owner}/{name}.git/git-receive-pack` | Push, after the ref check below. |

Rules:

- The caller authenticates with a runner access token (`rnr` claim). The token's device must be the runner device of a session whose project has the repository `{owner}/{name}`; anything else is `404`, so the proxy reveals nothing about other repositories.
- The proxy forwards to `https://github.com/{owner}/{name}.git/...` with the user's token as a Basic `Authorization` header, as the runner does today, and never follows a redirect to another host.
- For `git-receive-pack` it reads the command list at the start of the body (pkt-lines up to the flush packet, at most 64 KB, decompressed first when the body has `Content-Encoding: gzip`). Every command must update `refs/heads/<session branch>` and none may delete it; otherwise it answers `403` with a message git prints to the agent, and forwards nothing. The bytes already read are then sent followed by the rest of the body.
- Bodies are streamed in both directions and never held in memory, because the backend runs with a small heap and a clone can be large.
- A missing or revoked GitHub grant is reported as `401` with the same message the repository operations give today.
- `pointer.repo_prepare` loses its `token` argument and gets the proxy URL as `cloneUrl`; `pointer.repo_publish` loses `token`. The runner adds its current access token as `http.extraHeader` for each git invocation, as it adds the GitHub token today. [03-protocol.md](../../03-protocol.md) is updated in the same commit.

## Live view (phase 1.4)

The live view is a VNC stream of the display, shown in the client by noVNC and relayed by the backend. It does not use the gateway protocol and the agent never sees it.

### Data model

```sql
ALTER TABLE remote_workspaces
    ADD COLUMN desktop_view_secret bytea;
```

The view password of the container, encrypted with the cipher used for provider keys and generated with the container ([container.md](container.md#live-view-stack-phase-14)). The full password is never stored. A workspace whose container predates phase 1.4 has no secret; its container is recreated on the next start because its spec hash differs ([container.md](container.md#outdated-containers)), and until then `available` is false.

### REST

| Endpoint | Description |
|---|---|
| `GET /sessions/{sessionId}/desktop` | `{ available, running, width, height }`. `available` is true for a server session whose runner reports `desktop` and whose container has a view secret; `running` is true once the display is up, read from the runner through the internal operation `pointer.desktop_status`. Sizes are screen pixels. |
| `POST /sessions/{sessionId}/desktop/ticket` | Issues a ticket for one stream connection: `{ ticket, expiresAt, path, password, width, height }`. `409 desktop_not_running`, `409 container_not_running`, `429 too_many_viewers` (the session already has `pointer.desktop.max-viewers` open streams), `404` for another user's session. |
| `GET /sessions/{sessionId}/desktop/stream?ticket=...` | WebSocket upgrade. The backend checks the ticket (single use, valid for 60 seconds, bound to the user and the session), connects to the container's `websockify` and relays binary messages in both directions. |

### Rules

- Only the session's owner gets a ticket. A ticket works once; reconnecting needs a new one. A ticket in the query string is acceptable because it is short-lived and single use; the long-lived access token never goes into a URL.
- The relay forwards the whole VNC protocol, handshake and authentication included, so the backend implements no RFB logic. Because `x11vnc` runs view-only and the client has only the view password, input messages from the client are ignored by the server.
- The viewer limit is enforced when a ticket is issued, and again when the stream is opened in case two tickets raced; an open stream is never closed to make room for a new one.
- The relay closes the connection when the container stops, when the runner disconnects and when the session is deleted or archived. Closing a connection never affects the agent or a tool call: the stream and the tools use different channels.
- The relay negotiates the `binary` WebSocket subprotocol when the client offers it and passes the same to `websockify`. Its maximum binary message size is raised well above the servlet container's default of a few kilobytes, since a framebuffer update is much larger; partial messages are forwarded as they arrive.
- Each viewer has a bounded send buffer. A viewer that cannot keep up is disconnected with a "too slow" close code; it never slows down `x11vnc`, the other viewer or the backend.
- `Origin` is checked against the allowed client origins (`pointer.auth.allowed-origins`).
- Nothing from the stream is stored, and the stream is not captured in the usage ledger.

### Client connection

The Rust core owns the gateway WebSocket ([ADR 0007](../../adr/0007-communication-model.md)). noVNC needs a WebSocket object in the page, so the frontend opens the stream connection itself, using the ticket and the backend URL it already knows. This is a deliberate exception to ADR 0007, recorded in [ADR 0028](../../adr/0028-gui-sandbox-in-the-session-container.md). The Tauri configuration sets no content security policy today (`csp: null`); if one is introduced, its `connect-src` must allow the backend's `ws` and `wss` origin.

As implemented in phase 1.4 ([03-protocol.md](../../03-protocol.md) section 9.1): `running` is true only when the runner reports both the display and the stream, and the status call is made only while the container is running and its runner connected, so opening the panel never wakes a stopped container. The relay is `DesktopStreamHandler` and `DesktopViewer` in the `container` module: the ticket is checked in the handshake (`403` otherwise), the backend connects to `websockify` with the JDK WebSocket client and the `binary` subprotocol when the viewer offered it, and the handler accepts partial messages, so no message size limit is raised. Each viewer has a queue drained by its own virtual thread; more than `pointer.desktop.viewer-buffer` (8 MB) queued, or a send blocked for `pointer.desktop.viewer-send-timeout` (10 s), closes it with `4408`. Other close codes: `1001` when the stream ends upstream (the container or `websockify` stopped) or the session is deleted or archived, `4429` over the viewer limit at open, `4503` when the stream address cannot be reached. The relay does not close streams when the runner reconnects: the runner's WebSocket reconnects regularly when its access token expires, while `websockify` keeps running, and a stopped container ends the stream upstream anyway. Tickets live in memory (one backend instance).

[03-protocol.md](../../03-protocol.md) gets section updates for the capability, the `images` field of results, the `images` and `marks` fields of the `tool_call` item, the repository proxy and the REST endpoints in the commit of the phase that introduces each.
