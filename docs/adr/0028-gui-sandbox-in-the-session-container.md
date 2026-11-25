# 0028. GUI sandbox in the session container

Status: Accepted. Builds on [ADR 0016](0016-remote-execution-in-containers.md) and [ADR 0027](0027-root-runner-and-repository-proxy.md).

## Context

F7 ([GUI testing sandbox](../features/f7-gui-sandbox/README.md)) lets the agent run the application it works on, look at it and operate it. Computer-use features of desktop assistants drive the user's own screen; on Windows, where Pointer's users are, that shares one pointer and one keyboard focus with the user, so the user cannot work while the agent tests. Phase 1.1 added a virtual display and the `desktop_*` executor operations to the runner of server sessions ([ADR 0027](0027-root-runner-and-repository-proxy.md)). This ADR records how the agent loop uses them.

Three facts shape the design:

- Vision models disagree on coordinates. Some answer in pixels of the image they were given, others (Qwen3-VL, Gemini) on a 0–1000 grid whatever the image size, and providers shrink large images before the model sees them.
- The OpenAI-compatible chat format allows images in user messages, not in tool messages.
- A server session's runner is offline between turns (its container stops after 30 idle minutes), so its capabilities are unknown exactly when the tool list of the next turn is built.

## Decision

- **No new container and no new service.** The display, input and capture live in the runner (`Xvfb`, `openbox`, `xdotool`, `scrot`, the `image` crate), and the agent loop sees eleven new built-in tools whose operations only an executor with the capability `desktop` performs: `desktop_start`, `desktop_launch`, `desktop_stop`, `desktop_screenshot`, `desktop_move`, `desktop_click`, `desktop_drag`, `desktop_scroll`, `desktop_type`, `desktop_key`, `desktop_wait`. They are Agent mode tools; observation tools default to `allow`, the rest to `ask`, which server sessions auto-approve. Separate tools rather than one `desktop` tool with an `action` argument, because separate schemas are easier to call correctly; their fixed cost is measured in [desktop.md](../benchmarks/desktop.md) and the single-tool form stays an option for phase 1.6.
- **Capabilities are persisted.** The gateway keeps `executor` and `desktop` for runner connections and stores the set of every `hello` in `devices.capabilities`. `ExecutionService` answers with the live set when the device is connected, the stored set otherwise, and for a runner that never connected with `pointer.container.desktop`. `ToolRegistry` drops desktop tools for a device without `desktop`, so the tool list of a turn does not change when its first call wakes the container.
- **Coordinates in the model's own space, mapped by the backend.** A model has `coordinate_system` (`pixels` or `normalized`). The screenshot sent to the model is the display scaled to fit 1280x800; with the default display of 1280x800 that is the identity for a pixel model. `DesktopCalls` converts every coordinate argument from the model's space to screen pixels before the `exec.request` (`round(v × screen / image)` for pixels, `round(n × (screen − 1) / 1000)` on the grid, so 1000 is the last pixel), rejects values outside the screenshot with `invalid_coordinates` instead of clamping, and converts coordinates in results back. It reads the display and image sizes from the latest `desktop_start` result or image of the session, or from `pointer.desktop_status` after a backend restart. The tool descriptions of the coordinate tools end with one sentence per convention, chosen by the model that receives the tool list. The client never needs the convention: items carry `marks`, the acted-on points in pixels of the stored image.
- **Screenshots are session attachments.** `exec.result` carries images as base64; the backend validates the type, signature, size and dimensions, stores each as an attachment with `origin = 'desktop'` and its dimensions, and keeps only references (`images`) on the `tool_call` item. Nothing in an item or an event holds image data.
- **Screenshots follow the tool results as one user message.** `ContextAssembler` renders a step as the assistant message, one tool message per call (the result text with the note `Screenshot attached.`), and, when any call of the step has images, one user message `Screenshots from this step, in call order:` with a line and a data URL per image. The message is a pure function of stored items, so the append-only invariant of [ADR 0024](0024-append-only-context.md) holds. A model without vision sees the same message with placeholders instead of images. Data URLs are cached in memory (24 MB) because every step re-renders every screenshot of the active context.
- **Vision gating.** The tools are offered only when the session's step model accepts images. A model that cannot see and calls one anyway, because it is the `fast` tier of escalation ([ADR 0026](0026-trajectory-aware-escalation.md)) or a failover model, gets `needs_vision` and nothing runs. On the `fast` tier this escalates the turn before the next step with the reason `vision`, deterministically and outside the struggle score; `StruggleSignals` ignores such calls. A turn whose active context already holds screenshots starts on the step model.
- **Live view through VNC and noVNC (phase 1.4).** The container runs `x11vnc -viewonly` and `websockify` on the display, the backend authorizes a single-use ticket and relays the WebSocket, and the desktop frontend shows it with noVNC. The frontend opens this one WebSocket itself, because noVNC needs a WebSocket object in the page; this is a deliberate exception to [ADR 0007](0007-communication-model.md), limited to a read-only stream.

## Consequences

- An agent in a server session can build, launch and operate a Linux GUI application end to end and see the result after every input, without disturbing the user. A Linux pass does not prove that the Windows build looks the same.
- Screenshots are the largest content of a GUI turn (about a thousand input tokens each on most providers), and until image masking (phase 1.3) every screenshot of the active context is resent on every step.
- Every request of a server session with the desktop tools carries their schemas, a fixed addition to the cached prefix.
- A provider that rejects an image in a user message right after tool messages, or two user messages in a row, cannot be used for GUI work; merging messages would rewrite earlier ones and break the cache prefix. The live check in [desktop.md](../benchmarks/desktop.md) records which test providers accept both.
- Screen content is untrusted input: a page can address the agent. The tool descriptions tell the model to treat screen text as data; what an injected instruction can reach is bounded by the container and the repository proxy ([ADR 0027](0027-root-runner-and-repository-proxy.md)). Cutting outbound network for GUI sessions is a possible later control.
- Screenshots stay in the database with the session. A long GUI session can store hundreds of megabytes; phase 1.3 measures the growth and decides on retention.

## Rejected alternatives

- **Control the user's real desktop.** Shares the pointer and focus with the user on Windows and is not reproducible.
- **A separate sandbox service or container per session.** Duplicates the checkout, the toolchains and the lifecycle the runner container already has.
- **Clamp coordinates into the screen.** Hides a wrong convention or a misread screenshot behind clicks that land somewhere else.
- **Images in the tool messages.** Not part of the OpenAI-compatible format; providers drop or reject them.
- **One synthetic user message per call.** More messages and more breaks between tool results for no gain; one message per step keeps the step together.
- **JPEG frames through the gateway for the live view.** Simpler to secure, but VNC gives smoother video at lower bandwidth and a ready path to user takeover.
