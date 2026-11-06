# Architecture Decision Records

Each ADR captures one decision: its context, the decision itself, consequences and rejected alternatives.
ADRs are immutable once accepted. A changed decision gets a new ADR that supersedes the old one.

File name: `NNNN-short-title.md`. Status: `Proposed`, `Accepted`, `Superseded by NNNN`.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-server-side-agent-orchestration.md) | Server-side agent orchestration with client executors | Accepted |
| [0002](0002-openai-compatible-byok-providers.md) | Bring-your-own-key OpenAI-compatible providers | Accepted |
| [0003](0003-local-models-via-client-relay.md) | Local models through a client relay | Accepted |
| [0004](0004-desktop-stack.md) | Desktop stack: Tauri, React, TypeScript | Accepted |
| [0005](0005-backend-stack.md) | Backend stack: Java 25, Spring Boot 4, PostgreSQL | Accepted |
| [0006](0006-authentication.md) | Authentication: email and password plus GitHub OAuth | Accepted, deep link part superseded by 0008 |
| [0007](0007-communication-model.md) | Communication model: REST commands, WebSocket owned by the Rust core | Accepted |
| [0008](0008-github-sign-in-loopback-redirect.md) | GitHub sign-in through a loopback redirect | Accepted |
| [0009](0009-context-compaction-and-plan-approval.md) | Context compaction and plan approval | Accepted |
| [0010](0010-mcp-servers-on-the-device.md) | MCP servers run on the user's device | Accepted |
| [0011](0011-session-worktrees.md) | Sessions in git worktrees | Accepted |
| [0012](0012-lombok-for-boilerplate.md) | Lombok for constructor injection and entity getters | Accepted |
| [0013](0013-semantic-code-index.md) | Semantic code index | Accepted |
| [0014](0014-agent-proposed-custom-tools.md) | Agent-proposed custom tools | Accepted |
| [0015](0015-telegram-remote-control.md) | Telegram remote control | Accepted |
| [0016](0016-remote-execution-in-containers.md) | Remote execution in containers | Accepted |
| [0017](0017-server-deployment.md) | Server deployment | Accepted |
