# F4. Agent memory

Status: Approved · Requirement ids: MEM-1 to MEM-7 · Depends on: context assembly (CTX-1 to 5), embedding model setting (CTX-7); fast model role from [F3](f3-model-routing.md) is used when present.

## Goal

Persistent memory across sessions, in the spirit of Claude Code memory: short typed facts about the user, their preferences and corrections, and the project, saved by the agent or the user, shown to the agent as an index in every session and read in full on demand. Memory lives on the backend, so it follows the user to every device, to server sessions and to Telegram. Later phases add suggested memories extracted from finished sessions and search over past sessions.

## Builds on

- `agent/ContextAssembler.systemPrompt(mode, globalInstructions, project)`: the place to add a memory section.
- `agent/AgentRunner` tool handling for server-side tools (`PlanTool`, `ProposeTool`) and `execution/ServerOperation` (as `rag/SemanticSearchOperation`).
- `rag/Embedder` and the user's `embedding_model_id`; `vector` columns without a fixed dimension (V14).
- Approvals with a user comment on rejection (`session/ApprovalService`).
- Project rules from `AGENTS.md` stay the shared, versioned instructions; memory is personal and not in the repository.

## Requirements

| Id | Requirement |
|---|---|
| MEM-1 | A memory has a scope (`user` or a project), a kind (`user`, `feedback`, `project`, `reference`), a unique name per scope, a one-line description and Markdown content. |
| MEM-2 | Every turn's system prompt contains an index of the user's and the project's memories (kind, name, description), limited to a token budget; pinned memories are included in full. |
| MEM-3 | The agent can save, update, read, search and delete memories with tools; every change appears in the conversation as an item with an undo action. |
| MEM-4 | The user manages memories in the desktop (list, edit, pin, delete) and saves one from Telegram with `/remember`. |
| MEM-5 | After a session becomes idle, the backend extracts suggested memories from it (corrections, rejected approvals with reasons, stated preferences); the user accepts, edits or dismisses them. |
| MEM-6 | Memories keep usage data (last read, read count) and the session they came from; the agent is told to verify file and symbol references in a memory before acting on them. |
| MEM-7 | The agent can search summaries of the project's past sessions. |

## Design

### Data model (`Vn__memories.sql`)

```sql
CREATE TABLE memories (
    id                uuid PRIMARY KEY,
    user_id           uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id        uuid REFERENCES projects (id) ON DELETE CASCADE,
    kind              text NOT NULL CHECK (kind IN ('user', 'feedback', 'project', 'reference')),
    name              text NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{1,63}$'),
    description       text NOT NULL,
    content           text NOT NULL,
    status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suggested', 'dismissed')),
    pinned            boolean NOT NULL DEFAULT false,
    source            text NOT NULL CHECK (source IN ('agent', 'user', 'extracted', 'telegram')),
    source_session_id uuid REFERENCES sessions (id) ON DELETE SET NULL,
    embedding         vector,
    embedding_model_id uuid REFERENCES models (id) ON DELETE SET NULL,
    read_count        integer NOT NULL DEFAULT 0,
    last_read_at      timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE UNIQUE INDEX memories_scope_name_idx
    ON memories (user_id, coalesce(project_id, '00000000-0000-0000-0000-000000000000'::uuid), name)
    WHERE status <> 'dismissed';

CREATE TABLE memory_revisions (
    id         uuid PRIMARY KEY,
    memory_id  uuid NOT NULL REFERENCES memories (id) ON DELETE CASCADE,
    description text NOT NULL,
    content    text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE session_digests (
    session_id  uuid PRIMARY KEY REFERENCES sessions (id) ON DELETE CASCADE,
    project_id  uuid NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    summary     text NOT NULL,
    last_seq    bigint NOT NULL,
    embedding   vector,
    updated_at  timestamptz NOT NULL
);
```

`memory_revisions` stores the previous version on every update and backs undo. `session_digests` is used from phase 4 only.

### Backend: new module `memory`

| Class | Responsibility |
|---|---|
| `MemoryService` | CRUD with validation, revisions, embeddings on write (skipped without an embedding model), usage counters. |
| `MemoryController` | `/api/v1/memories?projectId=&status=`, `POST`, `PATCH /{id}`, `DELETE /{id}`, `POST /{id}/accept`, `POST /{id}/dismiss`, `POST /{id}/undo`. |
| `MemoryIndex` | Builds the prompt section: pinned memories in full, then index lines `- [kind] name: description`, project scope first, ordered by similarity to the turn's last user message (when embeddings exist) then by `last_read_at`; budget 1,500 tokens, the rest summarized as "N more memories, use memory_search". |
| `MemoryTools` | Server-side tool operations below. |
| `MemoryExtractor` | Phase 3: suggested memories from an idle session. |
| `SessionDigester` | Phase 4: per-session summaries for history search. |

### Prompt section

Added by `ContextAssembler.systemPrompt` after project rules:

```
## Memory
Facts saved in earlier sessions. Read a memory with memory_read before relying on its details.
Memories can be outdated: verify file paths, symbols and commands they mention before acting on them.
Save a memory when the user states a preference, corrects you, or you learn a non-obvious project fact
that is not in the code or AGENTS.md. Do not save what the repository already records.
<pinned memories in full>
<index lines>
```

### Tools (registry entries, `source = builtin`, all modes)

| Tool | Arguments | Policy default |
|---|---|---|
| `memory_save` | `scope: user\|project, kind, name, description, content` (create, or update by name) | `allow` |
| `memory_read` | `names: string[]` (max 10) | `allow` |
| `memory_search` | `query, limit?` (vector over memories, lexical fallback) | `allow` |
| `memory_delete` | `name, scope` | `ask` |

Writes produce an item of type `memory_change` `{memoryId, action: created|updated|deleted, name, scope}` that the desktop renders with an Undo button (`POST /memories/{id}/undo`).

### Extraction (phase 3)

- Trigger: session idle for 15 minutes after a completed turn with at least 3 user messages, or archived; at most once per session per new `last_seq`.
- Input: user messages, rejected approvals with their comments, plan rejections, the compaction summary if present, plus the current index to avoid duplicates. Model: the `fast` role when routing is configured, else the session model.
- Output: strict JSON `[{scope, kind, name, description, content, evidence}]`, max 5; stored with `status = suggested`, `source = extracted`.
- Desktop shows a badge "N suggested memories" on the project; accepting sets `active`.

### Session history (phase 4)

- `SessionDigester` writes `session_digests` on the same idle trigger: a 200-word summary (goal, outcome, files, decisions) and its embedding.
- Tool `search_sessions {query, limit?}` returns digests with session title, date and id; the agent can quote them, and the desktop links the session.

### Desktop

- Settings → Memory (user scope) and Project → Memory tab: list with kind filter, search, edit dialog, pin, delete, revision history, suggested section.
- Conversation: `memory_change` items with Undo; `memory_read` shown as a compact tool call.
- Composer: `#` prefix to save the rest of the message as a user memory without sending it to the agent.

### Telegram

- `/remember <text>`: saves a `user` kind memory to the current project, name derived by the fast model or slug of the first words.
- `/memories`: last 10 memories of the current project.

## Phases

1. Table, service, REST, desktop management, prompt section with index and pinned memories.
2. Agent tools with `memory_change` items and undo; Telegram `/remember`.
3. Suggested memories from idle sessions.
4. Session digests and `search_sessions`.

## Tests

- `MemoryService`: name rules, scope uniqueness, revisions and undo, dismissed names reusable.
- `MemoryIndex`: budget cut-off, ordering with and without embeddings, pinned first.
- Memory tools through `AgentRunner` with a scripted fake LLM: save, update by name, read counters, delete approval.
- `MemoryExtractor`: JSON parsing, duplicates against the index, max count, no run when nothing changed.

## Thesis metrics

- Preference retention: 10 scripted preferences or corrections stated in one session, then a task in a new session where each applies; share followed with memory on vs off.
- Prompt overhead of the memory section in tokens versus number of memories.
- Suggested memory precision: share accepted without edits over real use.

## Open questions

- Sharing project memories between users is out of scope while there is no team model.
