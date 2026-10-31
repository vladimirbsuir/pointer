# 0007. Communication model: REST commands, WebSocket owned by the Rust core

Status: Accepted

## Context

The desktop client and the backend exchange three kinds of traffic:

- user-initiated commands and queries (sign-in, CRUD, sending messages, approvals);
- realtime session events streamed to the client;
- requests from the backend that the device must serve: tool execution and local LLM relay.

Inside the desktop app, either the React UI or the Rust core could own the realtime connection.

## Decision

- **REST** carries all user-initiated commands and queries. They are request-response by nature, easy to retry with idempotency keys and documented with OpenAPI. Telegram and other future clients call the same application services.
- **WebSocket** carries only server-pushed and server-initiated traffic: session events and deltas, executor operations, relay.
- Session events have a per-session sequence number. Stored events are replayable, so clients recover after disconnects without losing data. Streaming deltas are transient.
- The **Rust core** owns the WebSocket connection and the refresh token. Execution and relay run entirely in Rust without passing through the WebView. The UI receives session events from the core through Tauri events and calls REST with access tokens it gets from the core.

## Consequences

- Execution keeps working while the UI reloads or a window is closed to the tray.
- Large file contents and relay streams do not cross the Rust-JavaScript boundary.
- One connection per device serves all sessions and all roles (viewer, executor, relay).
- The UI must merge REST history with live events; the event `seq` makes this deterministic.
- Two channels to maintain instead of one, but each is simpler than a combined design.

## Rejected alternatives

- **Everything over WebSocket:** request-response over a socket reimplements HTTP semantics, error handling and documentation by hand.
- **UI owns the WebSocket:** every tool call and relay chunk would cross from JavaScript to Rust and back, and execution would stop whenever the WebView reloads.
- **Server-sent events for push plus REST for execution results:** works for events but makes the executor channel awkward, since the backend must initiate execution requests.
