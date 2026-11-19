# F7. Desktop client

Part of [F7](README.md). Covers the client side of GUI-5 and GUI-7. All user-visible strings go through the existing i18n (NFR-9).

## Conversation (phase 1.2)

A `tool_call` item of a desktop tool is shown like other tool calls, with an action summary instead of raw arguments:

| Tool | Summary |
|---|---|
| `desktop_click` | `Click (640, 360)`, with the button and click count when not a single left click; `Click "Save"` when the call used an `elementId` and the tree result is known |
| `desktop_type` | `Type "text…"` with the text shortened |
| `desktop_key` | `Press ctrl+s` |
| `desktop_drag`, `desktop_scroll`, `desktop_move` | Start and end points, direction and amount |
| `desktop_launch` | The command; `Exited with code N` when it exited early |
| `desktop_start`, `desktop_stop`, `desktop_wait`, `desktop_screenshot`, `desktop_tree` | The tool name and the main argument |

- **Thumbnail.** A call with `images` shows a thumbnail of the screenshot under the summary, loaded from `GET /sessions/{id}/attachments/{attachmentId}`. For `desktop_click`, `desktop_move`, `desktop_drag` and `desktop_scroll` a marker is drawn at the coordinates of the arguments on the thumbnail, so the user sees what the agent clicked on without opening anything. The marker is drawn by the client from the arguments; the stored image is the plain screenshot.
- **Full view.** Clicking the thumbnail opens the image at full size in an overlay, with the previous and next screenshots of the turn reachable by arrow keys.
- **Masked screenshots** (phase 1.3) look the same in the conversation. Masking only changes what the model sees; a small badge on a thumbnail is not needed.
- **Failures** show the error code and message as other tool calls do.
- **Models without vision.** A session whose model cannot see offers no desktop tools, so nothing is shown. If the user switches such a session to a model without vision after screenshots exist, the existing screenshots stay visible in the conversation.

Tool-call rows for desktop tools are folded by default in long turns like other tool calls, but the thumbnail of the last screenshot of a turn stays visible so a finished turn shows the final state of the application.

## Desktop panel (phase 1.4)

A server session whose executor reports `desktop` gets a Desktop panel, a pane next to the conversation in the same layout system as the Changes panel.

- **Content.** The live display, scaled to the pane keeping the aspect ratio, a status line with the resolution, the frame rate actually received and the last action (`Click (640, 360)`), and nothing else. It is read-only: pointer and keyboard events over it do nothing, and the cursor does not change.
- **Watching.** Opening the panel sends `desktop.watch` with `watching: true`; closing it, hiding the pane, switching session or window blur for more than a few seconds sends `false`. A session nobody watches costs no capture and no bandwidth.
- **No display yet.** Before `desktop_start` the panel shows the empty state "The agent has not started a display yet". After `desktop.ended`, or when the container is stopped, it shows the last screenshot attachment of the session, dimmed, with the note "The display is not running".
- **Reconnects.** The panel resends `desktop.watch` after the gateway reconnects. Frames are applied only if their `seq` is newer than the last shown.
- **Availability.** The panel entry appears only when the session is a server session and its runner reported `desktop`; it is hidden for sessions on the user's computer in stage 1.

The Rust core owns the WebSocket ([ADR 0007](../../adr/0007-communication-model.md)); it forwards `desktop.frame` to the frontend as an event with the base64 JPEG, and the frontend draws it into an `<img>` or canvas. Frames are not kept in the session store.

## Settings

No user-facing settings in stage 1. Display size, settle delay, image masking and limits are application properties. Whether the feature needs a switch per session or per project is decided after the benchmark; until then the existing per-tool permission policies are the control (a user can set the desktop tools to `deny`).

## Telegram

Unchanged. The bot shows no images today; see the open question in the [README](README.md#open-questions).
