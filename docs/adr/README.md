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
| [0006](0006-authentication.md) | Authentication: email and password plus GitHub OAuth | Accepted |
| [0007](0007-communication-model.md) | Communication model: REST commands, WebSocket owned by the Rust core | Accepted |
