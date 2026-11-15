# F4. Agent memory

Status: Implemented ([ADR 0019](../adr/0019-agent-memory.md)) · Requirement ids: MEM-1 to MEM-7 · Depends on: context assembly (CTX-1 to 5), embedding model setting (CTX-7); fast model role from [F3](f3-model-routing.md) is used when present.

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
| MEM-3 | The agent can save, update, read, search and delete memories with tools; every change it makes can be undone from its tool call in the conversation. |
| MEM-4 | The user manages memories in the desktop (list, edit, pin, delete), saves a note by starting a message with `#`, and from Telegram with `/remember`. |
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
| `memory_delete` | `name, scope` | `allow` |

The tools are ordinary `tool_call` items; `memory_save` and `memory_delete` return `{memoryId, action: created|updated|deleted, name, scope}` and the desktop renders an Undo button on them (`POST /memories/{id}/undo`). Deleting only sets `status = deleted`, so every agent change is reversible and `memory_delete` needs no approval by default; a user who wants one sets the tool policy to `ask`.

Implementation notes: the module is `dev.pointer.memory` (`MemoryStore` on `JdbcClient`, `MemoryService`, `MemoryPrompt`, `MemoryOperations` registering the four `ServerOperation` beans, `MemoryEmbeddings`); migration V20 seeds the tools with kind `read`. Embeddings are computed in a virtual thread after the transaction commits and are optional: without an embedding model search is full-text only and the index keeps its default order. The index is reordered by similarity to the turn's message only when it does not fit the budget.

### Extraction and session digests (phases 3 and 4)

One model call per session produces both the session summary and the suggested memories (`MemoryCurator`):

- Trigger: a scheduled job every minute (`pointer.memory.curation-interval`) picks up to 5 sessions that are `idle`, have a model, at least 3 user messages (`curation-min-messages`), were last active more than 15 minutes ago (`curation-idle`) or are archived, were active within the last 3 days (`curation-max-age`, so a first run does not process the whole history), and have events after the last processed `seq`. A failed call is retried on later runs up to 3 times. `pointer.memory.curation-enabled` turns the job off (the integration tests do).
- Input: the transcript of the session (user and agent messages, plans with their approval state, compaction summaries, tool calls with denial messages), at most 24,000 characters keeping the latest part, plus the names and descriptions of existing, suggested and dismissed memories of the user and the project so that nothing is suggested twice. Model: the session model.
- Output: JSON `{summary, memories: [{scope, kind, name, description, content}]}`. The summary (at most 150 words in English) is stored in `session_digests` with its embedding; up to 5 memories are stored with `status = suggested`, `source = extracted` and the source session. Invalid or duplicate names are skipped.
- `session_digests` (migration V21): `session_id`, `user_id`, `project_id`, `summary`, `processed_seq`, `attempts`, `suggested_count`, `embedding`, `embedding_model_id`, `updated_at`.
- Tool `search_sessions {query, limit?}` (up to 10) searches the digests of the project except the current session, by vector similarity when the user has an embedding model and by full-text rank otherwise, and returns `{sessionId, title, date, summary, score}`.
- The desktop shows suggested memories at the top of the memory list with Keep and Dismiss, and their number next to the Memory item of the project menu.

### Desktop

- Settings → Memory (user scope) and Project → Memory tab: list with kind filter, search, edit dialog, pin, delete, revision history, suggested section.
- Conversation: `memory_change` items with Undo; `memory_read` shown as a compact tool call.
- Composer: a message starting with `#` is saved with `POST /memories/remember` to the session's project instead of being sent; the name is a slug of the first words.

### Telegram

- `/remember <text>`: saves a `user` kind memory to the project of the selected session, or to the user scope without one; the name is a slug of the first words.
- `/memories`: last 10 memories visible in that project.

## Phases

1. Table, service, REST, desktop management, prompt section with index and pinned memories. Implemented.
2. Agent tools with undo; Telegram `/remember` and `/memories`; the composer `#` shortcut. Implemented and checked live with `glm-5.3-flash`: asked to remember a commit convention, the agent called `memory_save` (kind `feedback`, project scope, with a Why line); in a new session it called `memory_read` and wrote a one-line Conventional Commits message.
3. Suggested memories from idle sessions. Implemented.
4. Session digests and `search_sessions`. Implemented and checked live with `glm-5.3-flash` and `nomic-embed-text`: after a three-message session about a cleanup script was archived, the curator wrote an accurate digest; it suggested nothing because the agent had already saved the preference and the plan itself. In a new session the agent called `search_sessions`, found that session by meaning (score 0.73) and then read the related memories.

## Tests

- `MemoryService`: name rules, scope uniqueness, revisions and undo, dismissed names reusable.
- `MemoryIndex`: budget cut-off, ordering with and without embeddings, pinned first.
- Memory tools through `AgentRunner` with a scripted fake LLM: save, update by name, read counters, delete approval.
- `MemoryExtractor`: JSON parsing, duplicates against the index, max count, no run when nothing changed.

## Thesis metrics

- Preference retention, measured in [benchmarks/memory.md](../benchmarks/memory.md): ten preferences stated once in Russian were followed in new sessions in 10 of 10 cases with memory against 4 of 10 without (8 of 8 against 2 of 8 for the preferences not derivable from the repository). The agent saved all of them without being asked; the index of ten memories costs about 270 input tokens.
- Suggested memory precision: share accepted without edits over real use; to be collected from daily use.

## Open questions

- Sharing project memories between users is out of scope while there is no team model.
