# 0011. Sessions in git worktrees

Status: Accepted.

## Context

REV-5 asks that a session can run in its own git worktree so several agents work on one project in parallel without touching each other's files, and that the result can be merged or discarded.
Local paths are known only to the device ([04-data-model.md](../04-data-model.md)), the agent loop runs on the backend ([ADR 0001](0001-server-side-agent-orchestration.md)), and every file operation already reaches the device through `exec.request`.

## Decision

- A session is either a regular session working in the project folder or a worktree session. The choice is made when the session is created and before its first turn.
- The backend stores the worktree branch, the base branch and a state (`active`, `merged`, `discarded`) on the session. The branch is `pointer/<first 8 characters of the session id>`.
- The device places the worktree at `<app data>/worktrees/<session id>`, outside the project, so the project needs no `.gitignore` changes. The path is derived, never sent.
- Every `exec.request` of an active worktree session carries `worktree` with the branch name. The core then resolves paths, runs commands and reads project instructions in the worktree instead of the project folder. A missing worktree is rejected with `worktree_missing`.
- Lifecycle operations are executor operations started by REST commands on the backend: `pointer.worktree_create`, `pointer.worktree_status`, `pointer.worktree_merge`, `pointer.worktree_discard`. The core runs the system `git`.
- Merge commits pending changes in the worktree, then runs `git merge --no-ff` of the branch into the project folder, which must have the base branch checked out. A conflict aborts the merge and leaves everything as it was. After a merge or a discard the worktree and the branch are removed and the session continues in the project folder.
- Worktree sessions require the project folder to be the top level of a git repository with a checked-out branch.

## Consequences

- Sessions in worktrees run fully in parallel; file change records, reverts and rewinds work unchanged because their paths stay relative to the session root.
- Merging needs the base branch to be checked out in the project folder; the user resolves this in git if not.
- The terminal panel and the file tree keep showing the project folder.
- Dependencies (`node_modules`, build output) are not shared between the project folder and worktrees; the agent installs them in the worktree when needed.

## Rejected alternatives

- **Worktrees inside the project (`.pointer/worktrees`).** Shows up in file listings, search and `git status` of the project unless ignored.
- **The backend sends the worktree path.** Paths are device-local and the backend never stores them.
- **Squash merge.** Loses the agent's intermediate commits; a no-fast-forward merge keeps them and is still one revertable merge commit.
