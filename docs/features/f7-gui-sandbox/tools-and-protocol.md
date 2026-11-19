# F7. Tools and protocol

Part of [F7](README.md). Covers GUI-2 to GUI-5, GUI-7, GUI-8.

## Model-facing tools

Each tool has an executor operation of the same name, like `run_command`. All are offered in Agent mode only. `kind` is `read` for observation and `execute` for everything that changes state or starts processes; the default policy is `ask` as for other execute tools, and server sessions auto-approve it (PERM-6).

| Tool | Arguments | Result (text part) | Kind |
|---|---|---|---|
| `desktop_start` | `width?`, `height?` | `display`, `width`, `height`, `windowManager` | execute |
| `desktop_launch` | `command`, `workingDirectory?` | `processId`, `pid`, `logPath`, `exited?`, `exitCode?`, `logTail?` | execute |
| `desktop_stop` | `processId` | `stopped` | execute |
| `desktop_screenshot` | `region?` (`x`, `y`, `width`, `height`) | `width`, `height`, image | read |
| `desktop_move` | `x`, `y`, `observe?` | `x`, `y`, image | execute |
| `desktop_click` | `x`, `y` or `elementId`, `button?` (`left`, `middle`, `right`), `clicks?` (1 to 3), `modifiers?` (`ctrl`, `shift`, `alt`, `super`), `observe?` | `x`, `y`, image | execute |
| `desktop_drag` | `fromX`, `fromY`, `toX`, `toY`, `button?`, `observe?` | image | execute |
| `desktop_scroll` | `x`, `y`, `direction` (`up`, `down`, `left`, `right`), `amount?` (ticks, default 3), `observe?` | image | execute |
| `desktop_type` | `text`, `observe?` | `typed` (characters), image | execute |
| `desktop_key` | `keys` (xdotool key names joined by `+`, for example `ctrl+s`, `Return`), `observe?` | image | execute |
| `desktop_wait` | `ms` (at most 10000), `observe?` | image | read |
| `desktop_tree` | `maxDepth?`, `maxNodes?`, `scope?` (`focused`, `all`) | `nodes` (phase 1.5) | read |

Rules:

- **Coordinates** are pixels of the display and of every screenshot, origin top left. Out-of-range values fail with `invalid_coordinates`; nothing is clamped silently.
- **`observe`** defaults to `true`. After an input operation the runner waits for the interface to settle and returns a screenshot, so the model does not need an extra call to see the effect. `observe: false` skips the capture for batched inputs, for example typing into several fields. The settle delay is `pointer.desktop.settle-ms` (default 300); it is a fixed wait, not change detection, and phase 1.6 checks whether that is good enough.
- **`desktop_type`** types through `xdotool type` with a small inter-key delay. Text over 2,000 characters fails with `text_too_long`; long input belongs in a file, not in a screen.
- **`desktop_key`** rejects names `xdotool` does not know with `invalid_key`, listing none: the model should use the standard names.
- **`desktop_click` with `elementId`** (phase 1.5) takes the id from the latest `desktop_tree` result, resolves it to the center of the element's bounds and clicks there. A stale id fails with `element_not_found`.
- **Errors** common to all: `desktop_unavailable` (the executor has no display stack; the tools are not offered then, so this only happens on a race), `display_not_started`, `display_running`, `process_not_found`, `too_many_processes`, `invalid_coordinates`, `invalid_key`, `text_too_long`, `element_not_found`.

The tool descriptions carry the usage guidance (see [agent-and-context.md](agent-and-context.md#guidance-in-the-tool-descriptions)).

## Executor operations and images in results

The operations are the tools above. `exec.request` is unchanged ([03-protocol.md](../../03-protocol.md) section 6.1); `timeoutMs` defaults to 30 seconds, and `desktop_launch` counts the grace period inside it.

One internal operation is added for the backend, never exposed to the model: `pointer.desktop_status` (no arguments) returns `{ running, width, height, processes }` and backs `GET /sessions/{sessionId}/desktop`. On a runner without the display stack it fails with `desktop_unavailable`.

`exec.result.result` of an operation that captures a screen gets one new field:

```json
{
  "x": 640,
  "y": 360,
  "images": [
    { "mediaType": "image/png", "width": 1280, "height": 800, "data": "iVBORw0KGgo..." }
  ]
}
```

`data` is base64. The backend does not keep it in the item: when `ExecutionService` receives a result with `images`, `DesktopImages` stores each one as an attachment (below), removes `images` from the result and stores a reference list on the item instead.

Limits: an image over `pointer.desktop.max-image-bytes` (default 2 MB) is never sent; the runner re-encodes it as JPEG and, if that is still too large, fails the operation with `image_too_large`. The gateway's maximum message size must allow the largest image plus its base64 overhead; the value is checked in phase 1.2 and raised in the gateway configuration if needed. `maxToolOutputBytes` applies to the text part only.

### `tool_call` item

Gets one new optional field, present on desktop tool calls with a captured screenshot:

```json
"images": [ { "attachmentId": "att_01JC...", "mediaType": "image/png", "width": 1280, "height": 800 } ]
```

`resultText`, which is what the model reads as the tool result, holds the text part (coordinates, process id, and so on) and for calls with an image a one-line note, `Screenshot attached.`. The image itself reaches the model as described in [agent-and-context.md](agent-and-context.md#how-screenshots-enter-the-context). `GET /sessions/{id}/attachments/{attachmentId}` serves it with the existing authorization; the upload endpoint stays for user images only.

## Capability and tool availability

- The `hello` message of a runner lists `capabilities: ["executor", "desktop"]` when its image has the display stack (`Xvfb` and `xdotool` found on the path), and `["executor"]` otherwise. `capabilities` gets the new value `desktop` in the `hello` table of [03-protocol.md](../../03-protocol.md) section 3.1. The runner token's `rnr` claim still restricts a runner to executor-type capabilities; the gateway is changed to accept `desktop` there and still refuses `viewer` and `relay`.
- `ExecutionService` records the capabilities of each device. `ToolRegistry.available` drops tools whose operation needs `desktop` when the session's device lacks it, in the same way it drops custom tools whose interpreter is missing today.
- The agent loop drops them again when the step model does not support vision ([agent-and-context.md](agent-and-context.md#vision-gating)).
- The desktop client ([ADR 0007](../../adr/0007-communication-model.md)) never reports `desktop` in stage 1, so local sessions never see these tools.

## Data model (`Vn__desktop_sandbox.sql`)

```sql
ALTER TABLE attachments
    ADD COLUMN origin text NOT NULL DEFAULT 'user' CHECK (origin IN ('user', 'desktop')),
    ADD COLUMN width integer,
    ADD COLUMN height integer;
```

Screenshots reuse the session-scoped attachments table: they are removed with the session and served by the existing endpoint. `origin` keeps them apart from the images the user attaches, so a listing of the user's attachments and any retention rule can tell them apart. The tool rows are inserted by the same migration with fixed ids in the style of V5, in the name order the context relies on ([ADR 0024](../../adr/0024-append-only-context.md)).

Update [04-data-model.md](../../04-data-model.md) in the same commit.

## Live view (phase 1.4)

The live view is a VNC stream of the display, shown in the client by noVNC and proxied by the backend. It does not use the gateway protocol and the agent never sees it.

### Data model

```sql
ALTER TABLE remote_workspaces
    ADD COLUMN desktop_view_secret text;
```

The view password of the container, encrypted with the cipher used for provider keys and generated with the container ([container.md](container.md#live-view-stack-phase-14)). The full password is never stored.

### REST

| Endpoint | Description |
|---|---|
| `GET /sessions/{sessionId}/desktop` | `{ available, running, width, height }`. `available` is true for a server session whose runner reports `desktop`; `running` is true once the display is up, read from the runner through the internal operation `pointer.desktop_status`. |
| `POST /sessions/{sessionId}/desktop/ticket` | Issues a ticket for one stream connection: `{ ticket, expiresAt, path, password, width, height }`. `409 desktop_not_running`, `409 container_not_running`, `429 too_many_viewers` (more than `pointer.desktop.max-viewers`), `404` for another user's session. |
| `GET /sessions/{sessionId}/desktop/stream?ticket=...` | WebSocket upgrade. The backend checks the ticket (single use, valid for 60 seconds, bound to the user and the session), connects to the container's `websockify` and relays binary messages in both directions. |

### Rules

- Only the session's owner gets a ticket. A ticket works once; reconnecting needs a new one. A ticket in the query string is acceptable because it is short-lived and single use; the long-lived access token never goes into a URL.
- The relay forwards the whole VNC protocol, handshake and authentication included, so the backend implements no RFB logic. Because `x11vnc` runs view-only and the client has only the view password, input messages from the client are ignored by the server.
- The relay closes the connection when the container stops, when the runner disconnects, when the session is deleted or archived, and after the viewer count limit is exceeded. Closing a connection never affects the agent or a tool call: the stream and the tools use different channels.
- `Origin` is checked against the allowed client origins.
- Nothing from the stream is stored, and the stream is not captured in the usage ledger.

### Client connection

The Rust core owns the gateway WebSocket ([ADR 0007](../../adr/0007-communication-model.md)). noVNC needs a WebSocket object in the page, so the frontend opens the stream connection itself, using the ticket and the backend URL it already knows. This is a deliberate exception to ADR 0007, recorded in ADR 0027, and the Tauri content security policy must allow the backend's `ws` and `wss` origin in `connect-src` (checked in phase 1.4).

[03-protocol.md](../../03-protocol.md) gets section updates for the capability, the `images` field of results, the `tool_call` item field and the REST endpoints in the commit of the phase that introduces each.
