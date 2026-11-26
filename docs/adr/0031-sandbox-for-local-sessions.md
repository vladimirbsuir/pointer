# 0031. Sandbox container for local sessions

Status: Accepted. Builds on [ADR 0016](0016-remote-execution-in-containers.md) and [ADR 0028](0028-gui-sandbox-in-the-session-container.md).

## Context

Stage 1 of F7 ([GUI testing sandbox](../features/f7-gui-sandbox/README.md)) gives server sessions a virtual display in their runner container. A local session runs its executor on the user's computer, a Windows machine in the common case, where there is no Linux display and where input injection would share the user's cursor and focus. Stage 2 ([stage-2-local-sessions.md](../features/f7-gui-sandbox/stage-2-local-sessions.md), GUI-11 and GUI-12) gives such a session a second executor that has the display.

The design sketch left five questions open. The user answered them on 2026-11-26: the sandbox runs on the backend host, not in the user's Docker; the code arrives as an archive snapshot, not through GitHub; the project opts in and every sync is approved; the sandbox is a closed box with its own tools.

## Decision

- **A sandbox is a session container of a second kind.** It is a row of `remote_workspaces` with `kind = 'sandbox'` and no repository, started from `pointer.container.sandbox-image` (`pointer-runner:gui` by default) as `pointer-sandbox-<session id>` with the volume of the same name. It shares with server sessions everything that is about containers: the runner binary, root with the reduced capability set, the start and its slots (`max-running` counts both kinds), the idle stop, the recreation of outdated containers, the view passwords and the live view relay. Everything that is about a server session (session lists, publishing, pull requests, file suggestions, automations) reads only `kind = 'session'`. A separate table and service would have duplicated the lifecycle for no gain.
- **The sandbox's executor is an ordinary runner device.** It has its own secret and token and connects through the gateway like a server session's runner, so the gateway and the runner did not change. `devices.session_id` ties runner devices to their session, because the cascade removes the workspace row before the after-commit listener of the deletion runs, and the device must still be revoked.
- **Routing by tool, decided in the backend.** `ExecutionService` asks a `SandboxProvider` whether a session is offered a sandbox (remote execution on, a sandbox image configured, the project's `sandbox_enabled`, a device that is not a managed runner) and which device is the session's sandbox, creating it on the first call. `ToolCallHandler` sends the desktop tools of a device without `desktop` and every tool whose operation starts with `sandbox_` to that device, without the session's worktree; all other tools keep going to the user's device. The runner sees only the operations it already knows: `sandbox_run` is sent as `run_command`. A target argument on the file and command tools was rejected: it would enlarge every schema and blur where an edit lands.
- **Created lazily, removed with the session.** A session that never calls a sandbox tool costs nothing. The container starts on the first call through the existing wake path, which shows `waiting_for_executor`; it is removed with its volume, and its device revoked, when the session is deleted.
- **A failed start fails the call.** The readiness hook now reports a failed container, and `ExecutionService` returns `executor_unavailable` with the start error instead of waiting until the user cancels. A failed workspace is queued again when the next call wakes it. This also changes server sessions, whose calls used to wait.
- **The model is told where the display is.** In a sandboxed session `desktop_start` says that the display runs in a Linux container on the server without the user's files, and the desktop tool descriptions name `sandbox_run` where they name `run_command`.

## Consequences

- An agent in a local Windows session can install packages, build and test a GUI application on Linux and look at it, while the user keeps working. Until phase 2.2 the project's own files do not reach the sandbox.
- The server pays for sandboxes like for server sessions: they take container slots and memory, and the same idle stop applies. Limits per user come in phase 2.4.
- A sandbox cannot reach services on the user's computer; a project that needs a database starts it in the sandbox. The user's local Docker remains a possible second kind of sandbox device behind the same `SandboxProvider`.
- Project files sent to the sandbox (phase 2.2) are a second copy on the server, which is why the project opts in and every sync is approved.
