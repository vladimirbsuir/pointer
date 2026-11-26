# 0032. Project snapshots through single-use transfers

Status: Accepted. Builds on [ADR 0031](0031-sandbox-for-local-sessions.md).

## Context

Phase 2.2 of [F7 stage 2](../features/f7-gui-sandbox/stage-2-local-sessions.md) (GUI-12) has to bring the files of a local project into the session's sandbox. The user chose an archive snapshot over git: any folder works, with or without a repository, and no GitHub token is involved. Edits stay on the user's computer, so a sync is repeated whenever the agent wants to build or run the latest code in the sandbox.

The two executors involved never talk to each other: the desktop client and the sandbox runner each hold one WebSocket to the gateway. The gateway accepts text messages of at most 4 MB, and all executor messages are JSON. A project without its dependencies and build output is usually a few megabytes as a gzip archive, but nothing bounds it to the gateway's limit.

## Decision

- **The archive travels over HTTP, not the gateway.** The backend opens a *transfer*: a temporary file, an upload ticket and a download ticket, each 32 random bytes, valid for 10 minutes and usable once. `PUT /api/v1/transfers/{upload ticket}` stores the body, refusing more than the transfer's limit; `GET /api/v1/transfers/{download ticket}` streams it. The endpoints are public, as the live view stream is, because the ticket is the credential: the runner's executor and the desktop client's executor then need no access token in the operations, only the server URL each already uses for its connection.
- **Two internal operations, orchestrated by the backend.** `sandbox_sync` is one model-facing tool performed as `pointer.workspace_snapshot` on the user's device (pack and upload) followed by `pointer.sandbox_extract` on the sandbox runner (download and unpack). The backend deletes the transfer when the call ends, so the server keeps no copy outside the sandbox volume. The snapshot runs first: a project that is too large or has nothing uploaded fails the call before the sandbox is started.
- **What is sent.** Regular files that the git ignore rules do not exclude (also outside a repository), without `.git`, symbolic links and secret-looking files (`.env*` other than examples, keys, certificates, credential files of package managers), within 10,000 files and 100 MB of content. The left-out files are named in the result, so the agent and the user see them. Modification times are kept, so incremental builds in the sandbox notice changed files; the executable bit is kept, or guessed from a `#!` line on Windows, so scripts like `gradlew` run.
- **Mirror semantics.** The runner records the files of the last extraction next to the workspace and, on the next sync, deletes those the new archive lacks. Files it did not extract (installed dependencies, build output, anything made with `sandbox_run`) stay, so a sync never throws away a `node_modules` or `target` that took minutes to produce.

Alternatives considered:

- Chunks over the gateway would have needed a resumable chunk protocol in both directions and would have competed with session events on the same socket.
- Git (a bundle or a push to a server-side bare repository) needs a repository, misses untracked files unless they are committed somewhere, and was rejected by the user.
- Streaming the upload straight into the download without a temporary file would avoid the disk but ties the two calls together in time; the sandbox may still be starting when the upload ends.

## Consequences

- Any local project, including one that is not a git repository, can be built and run in the sandbox. The cost is a full upload per sync; there is no delta transfer.
- A file that is ignored by `.gitignore` but needed by the build (a generated config, for example) does not reach the sandbox; the agent creates it there with `sandbox_run`.
- The public transfer endpoints accept uploads only for open transfers, each bounded in size and time. Temporary files of transfers that never complete are deleted when they expire and the next transfer is opened.
- The desktop client, the runner and the bench device construct their executor with their server URL; an executor without one rejects the operations with `transfer_unavailable`.
