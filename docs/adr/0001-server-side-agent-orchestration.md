# 0001. Server-side agent orchestration with client executors

Status: Accepted

## Context

An agent needs the user's files, shell and git, which exist only on the user's machine.
At the same time the project should have a substantial backend, and planned features (Telegram control, server-side execution) need sessions that exist independently of the desktop window.

Options considered:

- **A. Thick client.** The agent loop runs in the Tauri core. The backend handles only accounts and settings sync.
- **B. Server-side orchestration.** The agent loop runs on the backend. The client executes tool calls sent over a persistent connection.
- **C. Hybrid.** A local sidecar process runs the loop, the backend provides auxiliary services.

## Decision

Option B. The backend owns sessions, the agent loop, context assembly, the tool registry and provider calls.
The desktop client connects over WebSocket and acts as an **executor**: it receives tool calls, performs them locally and returns results.

Tool execution goes through an executor abstraction on the backend. The desktop executor is the first implementation.
Session events are published to any number of subscribers, so the client that displays a session does not have to be the executor.

## Consequences

- Telegram and other clients become additional subscribers without changes to the agent loop.
- A server-side container executor can be added later behind the same abstraction.
- Every tool call costs a round trip between backend and client. Acceptable compared to LLM latency.
- The session depends on the executor being online. The loop must pause and resume cleanly on disconnects.
- File contents pass through the backend, which must be documented to the user.
- The client-backend protocol becomes a core contract and needs its own specification.

## Rejected alternatives

- **A** leaves the backend nearly empty and makes multi-client sessions hard.
- **C** adds a third runtime and process management for a single developer with no clear gain.
