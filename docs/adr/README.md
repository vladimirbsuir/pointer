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
| [0013](0013-semantic-code-index.md) | Semantic code index | Accepted, search part superseded by 0018 |
| [0014](0014-agent-proposed-custom-tools.md) | Agent-proposed custom tools | Accepted |
| [0015](0015-telegram-remote-control.md) | Telegram remote control | Accepted |
| [0016](0016-remote-execution-in-containers.md) | Remote execution in containers | Accepted |
| [0017](0017-server-deployment.md) | Server deployment | Accepted |
| [0018](0018-hybrid-code-search.md) | Hybrid code search | Accepted |
| [0019](0019-agent-memory.md) | Agent memory | Accepted |
| [0020](0020-model-roles-and-usage-ledger.md) | Model roles and usage ledger | Accepted |
| [0021](0021-model-failover.md) | Model failover with retry and fallback lists | Accepted |
| [0022](0022-explore-sub-agent.md) | Explore sub-agent | Accepted |
| [0023](0023-issue-to-pull-request-automation.md) | Issue to pull request automation | Accepted |
| [0024](0024-append-only-context.md) | Append-only context with per-turn snapshots | Accepted |
| [0025](0025-cache-aware-compaction.md) | Cache-aware compaction | Accepted |
| [0026](0026-trajectory-aware-escalation.md) | Trajectory-aware model escalation | Accepted |
| [0027](0027-root-runner-and-repository-proxy.md) | Root in runner containers and a repository proxy | Accepted |
| [0028](0028-gui-sandbox-in-the-session-container.md) | GUI sandbox in the session container | Accepted |
| [0029](0029-image-masking.md) | Block-aligned masking of screenshots | Accepted |
| [0030](0030-accessibility-tree.md) | Accessibility tree of the virtual display | Accepted |
| [0031](0031-sandbox-for-local-sessions.md) | Sandbox container for local sessions | Accepted |
| [0032](0032-project-snapshots-through-transfers.md) | Project snapshots through single-use transfers | Accepted |
