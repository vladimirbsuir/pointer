# Pointer: Vision and Scope

## 1. Summary

Pointer is an agent-first desktop development environment for Windows.
The user describes a task in natural language, and an AI agent plans and carries it out inside the user's project: reading and editing files, running commands and reporting the result.
The user's role shifts from typing code to directing, reviewing and approving the agent's work.

The name is a nod to Cursor: a pointer is the other everyday name for the thing on screen you move around, and a familiar programming concept.

## 2. Problem

Classic IDEs are built around a human editing text. AI features are bolted on as autocompletion or a side chat.
Agentic development needs a different center of gravity:

- the primary object is a **session** with an agent working on a task, not a file in an editor;
- the user needs control over **what the agent is allowed to do** and a clear view of **what it changed**;
- the user wants to choose **any model**: commercial APIs with their own keys or local models;
- work should not be tied to one screen: sessions should be reachable from other clients (for example a phone).

## 3. Product concept

The interaction model follows Claude Code Desktop and Antigravity rather than a VS Code clone:

- The main screen is a list of **projects** and **sessions**, and a conversation view of the active session.
- The conversation shows the agent's messages, tool calls (file reads, edits, commands) and their results as they stream in.
- Edits are reviewed as **diffs**. Commands and file writes go through a **permission system**.
- A lightweight code viewer exists for reviewing and reading, not as the primary workspace.

## 4. Key architectural idea

The agent loop runs on a **cloud backend**. The desktop client is an **executor**: it owns the local file system, shell and git, and executes tool calls the backend sends to it.

```
 Desktop client (Tauri)                    Backend (Spring Boot)               LLM providers
 ┌──────────────────────┐   WebSocket     ┌───────────────────────┐   HTTPS   ┌──────────────┐
 │ UI (React)           │◄───────────────►│ Sessions, agent loop  │◄─────────►│ OpenAI-compat│
 │ Executor (Rust):     │  events,        │ Context assembly      │           │ endpoints    │
 │  files, shell, git   │  tool calls,    │ Tool registry         │           └──────────────┘
 │ Local LLM relay      │  tool results   │ Providers, users      │
 └──────────┬───────────┘                 │ PostgreSQL + pgvector │
            │ localhost                   └───────────────────────┘
      ┌─────▼─────┐
      │ Ollama etc│
      └───────────┘
```

This split keeps the core logic (orchestration, context, tools) in one place and makes the session independent of the client that displays it.
That independence is what later enables a Telegram client and server-side execution without redesign.

## 5. Principles

1. **Agent-first.** Every feature is evaluated by how it helps the user direct and supervise agents.
2. **User stays in control.** Nothing destructive happens without a policy that allows it or an explicit approval.
3. **Bring your own model.** Any OpenAI-compatible endpoint works, including local ones. No vendor lock-in.
4. **Client-agnostic sessions.** A session is a server-side entity streaming events to any number of subscribers.
5. **Pluggable execution.** Tool calls go through an executor abstraction. The desktop client is the first executor, not the only possible one.
6. **Extensible tools.** Tools live in a registry, not in hard-coded switch statements.

## 6. Scope

### 6.1 MVP

A version that is comfortable to use for real work:

- accounts: email and password, GitHub OAuth;
- provider configuration: OpenAI-compatible endpoints with user keys, local endpoints through the client relay;
- projects: open a local folder, project rules from `AGENTS.md`;
- sessions: create, list, rename, archive, persistent history, streaming responses, cancellation;
- modes: Ask, Plan, Agent;
- built-in tools: list, read, search, write, edit files, run commands;
- permissions: allow, ask or deny per tool, approval dialogs;
- review: diff of changes made in a session, revert per file;
- context: `@`-mentions of files, token usage display, automatic compaction;
- UI in English and Russian, extensible to other languages.

### 6.2 MVP+

- checkpoints and rollback to any point of a session;
- parallel agents, each in its own git worktree;
- MCP client support;
- integrated terminal panel;
- user-defined slash commands and skills.

### 6.3 Highlights

Distinguishing features planned after the MVP:

| Feature | Idea |
|---|---|
| Telegram remote control | A Telegram bot acts as another client of the same sessions: send prompts, watch progress, approve actions. |
| RAG over the codebase | Semantic search over the project. Code is chunked along syntax boundaries using an AST (tree-sitter), embeddings are stored in pgvector. |
| Agent-proposed custom tools | The agent notices a repeated pattern, proposes a new tool (name, schema, implementation). After user approval it is stored on the backend and becomes available in later sessions. |
| Remote execution | A second executor: a per-session Docker container on the server that clones the repository, runs the agent there and pushes a branch. |

### 6.4 Out of scope

- inline tab completion;
- a full-featured code editor competing with VS Code;
- real-time collaboration between users;
- platforms other than Windows (the stack allows them later, but they are not tested).

## 7. Success criteria

- The author can use Pointer instead of a commercial agent tool for a full day of work on Pointer itself.
- A session survives a client restart and network interruption without losing history.
- A model can be switched between a commercial API and a local model without changing anything else.
- Each highlight feature can be demonstrated end to end.
