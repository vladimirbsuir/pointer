# 0016. Remote execution in containers

Status: Accepted.

## Context

REM-3 asks that a session can run on a server-side executor: a container that clones a GitHub repository and pushes the result to a branch.
The agent loop already talks to an executor through the `execution` module and does not know where tools run ([ADR 0001](0001-server-side-agent-orchestration.md)).
The executor operations (files, search, commands, git worktrees, tree-sitter chunking, custom tools) are implemented once, in the Rust core of the desktop client.

The open questions were: what runs inside the container, how a user starts a server session, how the repository gets in and the result gets out, how approvals work there, what the desktop shows, and which isolation the container gets.

## Decision

- **The container runs a headless Rust runner.** The executor code moves from the Tauri crate into a library crate shared by the desktop and a new `pointer-runner` binary. The runner connects to the gateway like a desktop device with the single capability `executor`, so the agent loop, `exec.request`, approvals, RAG indexing and custom tools work without a second implementation. MCP servers stay on the user's device ([ADR 0010](0010-mcp-servers-on-the-device.md)); the runner reports none.
- **One container and one runner device per session.** Creating a server session creates a device of kind `runner`, a `remote_workspaces` row and a Docker volume for the checkout. The runner gets a random runner secret in its environment (stored only as a SHA-256 hash) and exchanges it at `POST /api/v1/runner/token` for the usual short-lived access tokens, bound to its device. Removing the workspace revokes the secret.
- **Lifecycle driven by the backend.** The `container` module talks to the Docker Engine API. The container is started when the session is created and whenever the executor is needed while it is stopped: `ExecutionService` calls an `ExecutorWakeup` extension point when a call starts waiting for its device, and the existing `waiting_for_executor` status covers the start-up time. A container idle for 30 minutes is stopped (archived sessions are idle too); the volume is kept, so the checkout survives. Deleting the session removes the container and the volume and revokes the runner device.
- **Projects get an optional GitHub repository.** A project stores `owner/name` of its repository: detected from the `origin` remote when a folder is linked, set by hand, or chosen when a project is created from a repository without a local folder. When the project has a repository, a new session can run on this computer or on the server. The Telegram `/new` command offers the server when no computer with the project is online.
- **Repository access through the user's GitHub account.** Server sessions need the `repo` scope. The user grants it once with the existing loopback OAuth flow ([ADR 0008](0008-github-sign-in-loopback-redirect.md)) from the settings; the token is stored encrypted in `user_identities` with the same cipher as provider keys. The token is handed to the runner only inside the arguments of the git operations that need it and is passed to git as an HTTP header, never written to the checkout or the environment.
- **Branch per session.** At start the runner clones the repository into the volume and creates `pointer/<short session id>-<slug>` from the default branch (or a chosen base). The agent works directly in that checkout; session worktrees ([ADR 0011](0011-session-worktrees.md)) are not offered for server sessions.
- **Commit and push once per turn.** When a turn that changed files ends, whatever its outcome, the backend asks the runner to commit all changes with a message built from the first line of the user's message and to push the branch. The pushed commit and branch are stored on the workspace and announced with `session.updated`. A Create pull request action calls the GitHub API with the user's token.
- **Auto-approval inside the container.** Server sessions are created with auto-approve on (PERM-6): file edits and commands only affect the isolated checkout, and nothing leaves the container except the push to the session branch. Plan approval, `propose_tool` and deny rules of the command policy still apply.
- **One general image.** `pointer-runner` ships in an image with git, a C toolchain, Python, Node.js, a JDK, Go and Rust. The container runs as a non-root user without extra capabilities, with limits for CPU, memory and process count, on a bridge network with outbound access, so the agent can install dependencies.
- **Desktop view.** A server session shows the chat, the Changes panel built from session events, the container status, the branch and the pull request link. `@` mentions in the composer and the attached file contents come from the runner through REST endpoints that run `pointer.suggest_files` and `pointer.read_attachment`. There is no terminal into the container.
- **Environment in the prompt.** `pointer.project_context` reports the executor's OS and shell, so the model is told Linux and bash on the runner instead of Windows and PowerShell.
- **Models.** Server sessions use cloud providers. Local providers are reached through a relay on the user's device ([ADR 0003](0003-local-models-via-client-relay.md)), which the runner does not offer, so a local model is rejected with a clear error for a server session.

## Consequences

- A session can work while the user's computer is off, and can be started and watched from Telegram.
- The executor code has one implementation for both executors; the cost is a Cargo workspace split and a Linux build of the runner inside the image.
- The backend host needs Docker. Without it the `container` module stays inactive and the server option is hidden.
- The image is large (several gigabytes) because of the bundled toolchains; projects needing other tools install them in the session.
- Auto-approval means a prompt injection can run arbitrary commands inside the container and push them to the session branch. The branch is never merged automatically, the default branch is never pushed, and the token reaches only the git processes of clone and push, which run outside the agent's turns.

## Rejected alternatives

- **Operations implemented on the backend over `docker exec`.** A second implementation of every executor operation in Java, including tree-sitter chunking and output streaming.
- **A container per project shared by sessions.** Saves start-up time but needs worktrees and locking inside the container; one container per session is simpler to reason about and clean up.
- **A commit after every file change.** Floods the branch with commits; a turn is the unit of work the user asked for.
- **Push only on request.** The point of a server session is that it works unattended; an explicit push would leave work stranded in a stopped container.
- **A project-specific image or devcontainer.** More flexible, but needs injecting the runner into arbitrary images. It can be added later behind a project setting.
- **A terminal into the container.** A separate streaming channel through the gateway for little benefit over the agent running commands.
