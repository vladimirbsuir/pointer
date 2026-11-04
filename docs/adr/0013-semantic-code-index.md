# 0013. Semantic code index

Status: Accepted.

## Context

CTX-7 and TOOL-14 ask for a semantic index of the project, with code chunked along AST boundaries, kept current when files change, and a `semantic_search` tool for the agent.
Project files live only on the device ([ADR 0001](0001-server-side-agent-orchestration.md)), models are reached through the user's OpenAI-compatible providers ([ADR 0002](0002-openai-compatible-byok-providers.md)), and PostgreSQL already ships with pgvector.
The roadmap left two open decisions for this milestone: embedding model defaults and chunk size.

## Decision

- **Chunking on the device.** The core parses files with tree-sitter and returns chunks through two internal executor operations: `pointer.index_scan` lists indexable files with content hashes, `pointer.index_chunks` returns the chunks of given files. Grammars: Rust, TypeScript, TSX, JavaScript, Python, Java and Go. Other text files are split into line windows.
- **Chunking algorithm.** Siblings of the syntax tree are merged greedily while a chunk stays within 2,000 characters. A node larger than that is split into its children, a leaf larger than that into line windows. A short header before such a node (signature, modifiers, doc comments, up to 400 characters, containing no other definitions) stays with the first chunk of its body instead of becoming a chunk of its own. Each chunk keeps its path, line range, node kind and symbol path (for example `SessionService.create`), which is prepended to the embedded text.
- **What is indexed.** Files not ignored by `.gitignore`, at most 256 KiB, valid UTF-8, at most 10,000 files per project. The index always reflects the project folder, not session worktrees.
- **Embeddings on the backend.** The user picks an embedding model from their providers in the settings; there is no built-in default. The backend calls `POST /embeddings` of that provider (through the relay for local providers) in batches of 32 inputs. The dimension is taken from the first response. Changing the model rebuilds the index.
- **Storage.** `code_chunks` holds chunk text and an `embedding vector` column without a fixed dimension; `indexed_files` holds the content hash per file; `index_states` holds the model, status and counters per project. Search is an exact cosine-distance scan filtered by project.
- **Synchronisation.** Indexing is started from the UI and runs in the background on the backend. It compares the scanned hashes with `indexed_files` and re-chunks only new and changed files, deleting chunks of removed files. Before every `semantic_search` the backend runs the same incremental pass, so edits by the agent, the user or git are picked up without a file watcher.
- **The tool.** `semantic_search` is a registry entry whose operation is executed by the backend itself. `ExecutionService` routes operations that have a server-side implementation to it instead of the device, so the agent loop is unchanged.

## Consequences

- Source code leaves the device in chunks and is stored on the backend, as conversation content already is, and is sent to the embedding provider the user chose.
- Freshness costs one scan per search: hashing the project files on the device, typically well under a second for projects of a few thousand files.
- Exact search scans every chunk of a project. That is fast for tens of thousands of chunks, which covers the projects Pointer targets.
- More languages need only a grammar crate and a few node kinds in the core.

## Rejected alternatives

- **HNSW index.** pgvector indexes need a fixed dimension, which would fix the embedding model for all users. It can be added later as partial indexes per dimension if search becomes slow.
- **Chunking on the backend.** The backend would have to fetch every file in full; tree-sitter is native code that fits the Rust core better.
- **File watcher in the core.** More moving parts and events for files nobody searches; the hash pass before a search gives the same freshness.
- **A fixed default embedding model.** Providers differ, and local runtimes offer different models; an explicit choice keeps BYOK consistent.
