# Pointer: Requirements

Priority levels:

- **MVP**: required for the first usable version;
- **MVP+**: next increment after the MVP;
- **HL**: highlight features (see [vision](00-vision.md#63-highlights)).

## 1. Glossary

| Term | Meaning |
|---|---|
| Project | A local folder registered in Pointer. Identified on the backend, mapped to a path on each device. |
| Session | A conversation between the user and an agent within a project, with its full history of messages and tool calls. |
| Turn | One user message and everything the agent does in response until it stops or asks for input. |
| Tool | A capability the agent can invoke, described by a name, description and JSON Schema of parameters. |
| Executor | A component that performs tool calls. The desktop client is the MVP executor. |
| Provider | A configured LLM endpoint: base URL, API key, available models. |
| Mode | A preset of allowed tools and system instructions: Ask, Plan, Agent. |
| Permission policy | A rule deciding whether a tool call is allowed, needs approval or is denied. |

## 2. Functional requirements

### 2.1 Accounts (AUTH)

| ID | Requirement | Priority |
|---|---|---|
| AUTH-1 | The user can register with email and password. | MVP |
| AUTH-2 | The user can sign in with email and password. | MVP |
| AUTH-3 | The user can sign in with GitHub OAuth. Desktop sign-in opens the system browser and returns to the app through a deep link. | MVP |
| AUTH-4 | The user can link a GitHub account to an existing email account and vice versa. | MVP |
| AUTH-5 | The client stays signed in across restarts using a refresh token stored in the OS credential store. | MVP |
| AUTH-6 | The user can sign out, which revokes the refresh token. | MVP |
| AUTH-7 | The user can change the password. | MVP |

### 2.2 Providers and models (PROV)

| ID | Requirement | Priority |
|---|---|---|
| PROV-1 | The user can add a provider by name, base URL and optional API key. | MVP |
| PROV-2 | The user can mark a provider as local. Requests to a local provider are relayed through the desktop client. | MVP |
| PROV-3 | The app fetches the model list from the provider's `/models` endpoint. The user can also add model ids manually. | MVP |
| PROV-4 | The user can test a provider connection and see a clear error on failure. | MVP |
| PROV-5 | API keys are stored encrypted on the backend and are never returned to clients after saving. | MVP |
| PROV-6 | The user can set a context window size and tool-calling support flag per model when the provider does not report them. | MVP |
| PROV-7 | The user can choose a default model, and override it per session. | MVP |
| PROV-8 | The provider layer allows adding specialized provider adapters (for example native Anthropic API) without changing the agent loop. | MVP+ |

### 2.3 Projects (PROJ)

| ID | Requirement | Priority |
|---|---|---|
| PROJ-1 | The user can open a local folder as a project. | MVP |
| PROJ-2 | The user sees a list of recent projects and can remove a project from the list. | MVP |
| PROJ-3 | If the project root contains `AGENTS.md` (or `CLAUDE.md`), its content is included in the agent's instructions. | MVP |
| PROJ-4 | The user can define global instructions applied to all projects. | MVP |
| PROJ-5 | The user can browse the project file tree and view files read-only with syntax highlighting. | MVP |

### 2.4 Sessions (SESS)

| ID | Requirement | Priority |
|---|---|---|
| SESS-1 | The user can create a session in a project. | MVP |
| SESS-2 | The user sees sessions grouped by project and can rename, archive and delete them. | MVP |
| SESS-3 | A session title is generated automatically from the first message and can be edited. | MVP |
| SESS-4 | Agent output (text and tool calls) streams to the client in real time. | MVP |
| SESS-5 | The user can stop the agent at any moment. A running command is terminated. | MVP |
| SESS-6 | The user can send a message while the agent is working. It is delivered at the next step boundary. | MVP |
| SESS-7 | Full session history is persisted on the backend and restored after client restart. | MVP |
| SESS-8 | If the executor disconnects, the session pauses and resumes when it reconnects. | MVP |
| SESS-9 | Several sessions can run concurrently. | MVP |
| SESS-10 | Session events can be consumed by any authenticated client, not only the one that started it. | MVP |
| SESS-11 | The user can attach images to a message for models that support vision. | MVP+ |

### 2.5 Modes (MODE)

| ID | Requirement | Priority |
|---|---|---|
| MODE-1 | **Ask** mode: read-only tools, the agent answers questions about the project. | MVP |
| MODE-2 | **Plan** mode: read-only tools, the agent produces a structured plan. The user can approve it, which switches the session to Agent mode and starts execution. | MVP |
| MODE-3 | **Agent** mode: all tools, subject to permission policies. | MVP |
| MODE-4 | The user can switch mode between turns. | MVP |

### 2.6 Tools (TOOL)

| ID | Requirement | Priority |
|---|---|---|
| TOOL-1 | `list_directory`: list entries of a directory. | MVP |
| TOOL-2 | `read_file`: read a file, optionally a line range. | MVP |
| TOOL-3 | `glob`: find files by pattern, respecting `.gitignore`. | MVP |
| TOOL-4 | `grep`: search file contents by regular expression, respecting `.gitignore`. | MVP |
| TOOL-5 | `write_file`: create or overwrite a file. | MVP |
| TOOL-6 | `edit_file`: replace an exact text fragment in a file. | MVP |
| TOOL-7 | `run_command`: run a shell command (PowerShell) in the project directory with a timeout, streaming output. | MVP |
| TOOL-8 | Built-in tools are stored in a tool registry on the backend together with their schemas. | MVP |
| TOOL-9 | File tools reject paths outside the project root. | MVP |
| TOOL-10 | Large tool outputs are truncated with a notice before being sent to the model. | MVP |
| TOOL-11 | `web_fetch`: fetch a URL and return readable text. | MVP+ |
| TOOL-12 | MCP servers configured by the user expose their tools to the agent. | MVP+ |
| TOOL-13 | The agent can propose a custom tool. After approval it is stored per user or per project and becomes available in later sessions. | HL |
| TOOL-14 | `semantic_search`: search the indexed codebase by meaning. | HL |

### 2.7 Permissions (PERM)

| ID | Requirement | Priority |
|---|---|---|
| PERM-1 | Each tool has a default policy: allow, ask or deny. Read-only tools default to allow, others to ask. | MVP |
| PERM-2 | An approval request shows what will happen: the diff for edits, the full command for shell calls. | MVP |
| PERM-3 | The user can approve once, approve for the rest of the session, or deny with an optional message to the agent. | MVP |
| PERM-4 | The user can define command rules by prefix (for example always allow `git status`, always deny `rm`). | MVP |
| PERM-5 | Policies can be set globally and overridden per project. | MVP |
| PERM-6 | A session-level "auto-approve" switch exists and is visibly indicated. | MVP |

### 2.8 Review (REV)

| ID | Requirement | Priority |
|---|---|---|
| REV-1 | Every file change made by the agent is recorded with its before and after content. | MVP |
| REV-2 | The user sees the list of files changed in a session and a diff for each. | MVP |
| REV-3 | The user can revert all agent changes to a file within the session. | MVP |
| REV-4 | The user can roll back the project and the conversation to any earlier turn (checkpoint). | MVP+ |
| REV-5 | A session can run in its own git worktree, and its changes can be merged or discarded. | MVP+ |

### 2.9 Context (CTX)

| ID | Requirement | Priority |
|---|---|---|
| CTX-1 | The system prompt is assembled from: base instructions, mode instructions, global and project rules, environment info (OS, date, project path). | MVP |
| CTX-2 | The user can mention files with `@` to attach their content to a message. | MVP |
| CTX-3 | The client shows current context usage relative to the model's context window. | MVP |
| CTX-4 | When context usage approaches the limit, older history is summarized automatically. The user can also trigger it manually. | MVP |
| CTX-5 | Token usage is recorded per turn and shown per session. | MVP |
| CTX-6 | Cost estimates are shown when model pricing is configured. | MVP+ |
| CTX-7 | The project can be indexed for semantic search, with code chunked along AST boundaries. The index updates on file changes. | HL |

### 2.10 Client and settings (UI)

| ID | Requirement | Priority |
|---|---|---|
| UI-1 | The interface is available in English and Russian. The user can switch language at runtime. New languages are added by adding a resource file. | MVP |
| UI-2 | Light and dark themes, following the system theme by default. | MVP |
| UI-3 | Messages render Markdown with highlighted code blocks. | MVP |
| UI-4 | The app shows a desktop notification when an agent finishes or needs approval while the window is not focused. | MVP |
| UI-5 | Keyboard shortcuts for common actions (new session, stop, switch mode). | MVP |
| UI-6 | Integrated terminal panel in the project directory. | MVP+ |
| UI-7 | User-defined slash commands that expand into prompts. | MVP+ |

### 2.11 Remote access and execution (REM)

| ID | Requirement | Priority |
|---|---|---|
| REM-1 | The user can link a Telegram account to a Pointer account. | HL |
| REM-2 | Through the Telegram bot the user can list sessions, send messages, receive progress and final answers, and approve or deny tool calls. | HL |
| REM-3 | A session can run on a server-side executor: a container that clones a GitHub repository and pushes results to a branch. | HL |

## 3. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | **Platform.** The desktop client runs on Windows 10 and 11 (x64). |
| NFR-2 | **Responsiveness.** The backend adds no more than 200 ms to the time to first token compared to calling the provider directly (local network conditions). |
| NFR-3 | **Resilience.** Network interruptions shorter than the reconnect window do not lose events: the client resumes from the last received event id. |
| NFR-4 | **Security, transport.** All client-backend traffic uses TLS in deployment. The WebSocket connection is authenticated with the access token. |
| NFR-5 | **Security, secrets.** Provider keys are encrypted at rest (AES-GCM) with a key outside the database. Passwords are hashed with a modern adaptive algorithm. |
| NFR-6 | **Security, local execution.** The executor never performs a tool call that was not issued by the backend for an active session of the signed-in user. File access is limited to the project root. |
| NFR-7 | **Privacy.** File contents pass through the backend only as part of the model context and tool results. This is documented to the user. |
| NFR-8 | **Extensibility.** Adding a tool, provider adapter, executor type or client type does not require changes to the agent loop. |
| NFR-9 | **Internationalization.** No user-visible string is hard-coded in components. |
| NFR-10 | **Maintainability.** Code follows the rules in [AGENTS.md](../AGENTS.md). Core backend logic is covered by automated tests. |
| NFR-11 | **Observability.** The backend logs each turn with timing, token usage and tool call outcomes. |
| NFR-12 | **Deployment.** The backend and its database start with a single Docker Compose command. |
