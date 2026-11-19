# F7. Desktop client

Part of [F7](README.md). Covers the client side of GUI-3 (the coordinate setting), GUI-5 and GUI-7. All user-visible strings go through the existing i18n (NFR-9).

## Conversation (phase 1.2)

A `tool_call` item of a desktop tool is shown like other tool calls, with an action summary instead of raw arguments:

| Tool | Summary |
|---|---|
| `desktop_click` | `Click (640, 360)`, with the button and click count when not a single left click; `Click "Save"` when the call used an `elementId` and the tree result is known |
| `desktop_type` | `Type "text…"` with the text shortened |
| `desktop_key` | `Press ctrl+s` |
| `desktop_drag`, `desktop_scroll`, `desktop_move` | Start and end points, direction and amount |
| `desktop_launch` | The command; `Exited with code N` when it exited early |
| `desktop_wait` | `Wait for window "Settings"`, `Wait for "Listening on" in the log` or `Wait 2 s`; `not met after 60 s` when the condition did not hold |
| `desktop_screenshot` | `Screenshot`, or `Zoom` when the call used `zoom` |
| `desktop_start`, `desktop_stop`, `desktop_tree` | The tool name and the main argument |

Summaries show coordinates as the model wrote them, in its convention; the marker below is what shows the point on the image.

- **Thumbnail.** A call with `images` shows a thumbnail of the screenshot under the summary, loaded from `GET /sessions/{id}/attachments/{attachmentId}`. For `desktop_click`, `desktop_move`, `desktop_drag` and `desktop_scroll` a marker is drawn at the item's `marks` on the thumbnail, so the user sees what the agent clicked on without opening anything. `marks` are in pixels of the attached image, so the client needs no knowledge of the model's coordinate convention or of image scaling; the stored image is the plain screenshot.
- **Full view.** Clicking the thumbnail opens the image at full size in an overlay, with the previous and next screenshots of the turn reachable by arrow keys.
- **Masked screenshots** (phase 1.3) look the same in the conversation. Masking only changes what the model sees; a small badge on a thumbnail is not needed.
- **Failures** show the error code and message as other tool calls do.
- **Models without vision.** A session whose model cannot see offers no desktop tools, so nothing is shown. If the user switches such a session to a model without vision after screenshots exist, the existing screenshots stay visible in the conversation. A desktop call from a `fast` tier without vision shows as a failed call with `needs_vision`, followed by the existing `model_escalated` notice with the reason `vision`.

Tool-call rows for desktop tools are folded by default in long turns like other tool calls, but the thumbnail of the last screenshot of a turn stays visible so a finished turn shows the final state of the application.

## Desktop panel (phase 1.4)

A server session whose executor reports `desktop` gets a Desktop panel, a pane next to the conversation in the same layout system as the Changes panel.

- **Content.** The live display shown by the noVNC `RFB` class, scaled to the pane keeping the aspect ratio, with a status line with the resolution, the connection state and the last action (`Click (640, 360)`), and nothing else. It is read-only: the client sets `viewOnly`, the server ignores input as well, and the cursor over the display does not change.
- **Connecting.** Opening the panel calls `GET /sessions/{id}/desktop`; when `running`, it requests a ticket with `POST /sessions/{id}/desktop/ticket` and connects to the stream with the ticket and the returned view password. Closing the panel, hiding the pane or switching session disconnects, so a session nobody watches holds no stream. A hidden window keeps the connection for a short grace period before it drops it.
- **No display yet.** Before the display is running (`running: false`) the panel shows the empty state "The agent has not started a display yet" and checks again when a `desktop_start` tool call completes in the conversation. When the stream closes because the container stopped, it shows the last screenshot attachment of the session, dimmed, with the note "The display is not running".
- **Reconnects.** A dropped connection is retried with a new ticket, a few times with a growing delay, then the panel falls back to the last screenshot and a Reconnect button. Too many viewers (`429`) shows a message instead of retrying.
- **Availability.** The panel entry appears only when the session is a server session and `available` is true; it is hidden for sessions on the user's computer in stage 1, and for a server session whose container predates the live view until the container is recreated on its next start.
- **Too slow.** When the backend closes the stream because the viewer could not keep up, the panel reconnects once with a new ticket and, if that happens again, shows the last screenshot with a note that the connection is too slow for the live view.

The frontend opens the stream WebSocket itself, which is an exception to the rule that the Rust core owns the connection ([ADR 0007](../../adr/0007-communication-model.md)); see [tools-and-protocol.md](tools-and-protocol.md#client-connection). noVNC (MPL 2.0) is an npm dependency of the frontend. Nothing from the stream is stored.

## Settings

One new model option: **Coordinates** (`Pixels` or `0–1000 grid`) in the model options editor (`ModelOptionsEditor`), shown next to the vision switch and enabled only when vision is on. It sets `models.coordinate_system` ([tools-and-protocol.md](tools-and-protocol.md#coordinate-spaces)); the hint under it says to pick the grid for models documented to answer that way, such as Qwen3-VL and Gemini.

Nothing else is user-facing in stage 1. Display size, image limit, settle delay, image masking and limits are application properties. Whether the feature needs a switch per session or per project is decided after the benchmark; until then the existing per-tool permission policies are the control (a user can set the desktop tools to `deny`).

## Telegram

Unchanged. The bot shows no images today; see the open question in the [README](README.md#open-questions).
