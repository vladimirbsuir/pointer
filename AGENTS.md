# AGENTS.md

Rules for any AI agent or human contributor working in this repository.

## Project

An agent-first desktop development environment (in the spirit of Claude Code Desktop and Antigravity).
The agent loop runs on a cloud backend; the desktop client executes tools (file system, terminal, git) on the user's machine.

| Directory  | Contents                                             |
|------------|------------------------------------------------------|
| `backend/` | Spring Boot monolith (Java)                          |
| `desktop/` | Tauri application: `src-tauri/` (Rust) and `src/` (frontend) |
| `docs/`    | Project documentation (English, Markdown)            |
| `infra/`   | Docker Compose and deployment configuration          |

## Code style

- Do not write comments in code. This includes line comments, block comments and documentation comments (Javadoc, JSDoc, TSDoc, rustdoc).
- Make code self-explanatory instead: precise names, small focused methods, explicit types where they help.
- Tool directives that are syntactically comments are allowed only when a tool requires them (for example `// @ts-expect-error`, `// eslint-disable-next-line`). Prefer fixing the cause over suppressing it.
- Do not leave commented-out code, TODO markers or explanatory notes in source files. Track open work in `docs/` or issues.

## Commits

- Follow [Conventional Commits](https://www.conventionalcommits.org/).
- A commit message is a single subject line only: no body, no footers, no trailers (including `Co-Authored-By`).
- Format: `<type>(<scope>): <description>`, for example `feat(backend): add session repository`.
- Types: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, `style`, `revert`.
- Scopes: `backend`, `desktop`, `docs`, `infra`, `repo`. Omit the scope only for changes that span the whole repository.
- Description: English, imperative mood, lowercase, no trailing period, whole line at most 72 characters.

## Documentation

- All documentation is written in English and lives in `docs/`.
- Architectural decisions are recorded as ADRs in `docs/adr/`.
