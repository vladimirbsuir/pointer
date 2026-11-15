# 0023. Issue to pull request automation

Status: Accepted.

## Context

Remote execution ([ADR 0016](0016-remote-execution-in-containers.md)) lets a session run on the server without any computer of the user, and Telegram ([ADR 0015](0015-telegram-remote-control.md)) reports on it. The next step is a session nobody starts by hand: a GitHub issue becomes a server session that implements it, proves the result with the project's own check command and opens a pull request. Feature spec: [F1](../features/f1-issue-to-pr.md) (AUTO-1 to AUTO-6).

The open questions were: who drives a run across several agent turns, how the result is verified, how GitHub starts a run, and what happens when the run cannot finish.

## Decision

- **A run is a state machine around an ordinary server session.** The `automation` module creates the session through `RemoteSessionService` (Agent mode, auto-approve, the automation's model and budget) and sends the issue as the first user message. It then only listens: `AutomationRunner` is a `SessionEventListener` that reacts to `turn.completed`. The agent loop, the runner and the publish step are unchanged, and the session can be opened, read and continued like any other.
- **The backend verifies, not the model.** After a turn the work is committed and pushed, then the backend itself sends the check command to the container as a `run_command` with a 15 minute limit. Exit code 0 is the only success. On failure the last 200 lines of output go back to the agent as a follow-up message; after the configured number of checks the run fails. The model cannot skip or fake the check.
- **Every outcome leaves something reviewable.** Success opens a pull request with `Closes #N`, the agent's summary and a run table. A final failure, a used-up budget or an agent error opens a draft pull request with `Refs #N` and the failing output when anything was pushed. A turn that pushes nothing fails the run with `needs_input` and quotes the agent's answer on the issue; conversation through issue comments is out of scope.
- **One status comment per run.** The run posts a comment on the issue and edits it on every change instead of adding new ones.
- **Label trigger through a per-automation webhook.** Enabling the automation creates a repository webhook for `issues` events with the user's `repo` token. The URL contains the automation id and uses `pointer.public-url`; the endpoint is unauthenticated and checks `X-Hub-Signature-256` with a 32-byte secret stored encrypted like provider keys, compared in constant time. Without a public URL only manual runs work, which keeps local development simple.
- **One active run per issue.** A partial unique index on (`automation_id`, `issue_number`) for active statuses makes repeated webhook deliveries and double clicks a no-op.
- **The session budget is the run budget.** The automation's budget is set on the session, so the existing check before each agent step ([ADR 0020](0020-model-roles-and-usage-ledger.md)) stops the run; no second accounting.
- **Notifications are session events.** Every change appends `automation.updated` to the run's session. Telegram and the desktop read it like other events, so `automation` does not depend on `telegram`; `telegram` calls `AutomationService` only for `/issue`.
- **Recovery uses the same messages.** After a restart an interrupted turn gets a follow-up message asking the agent to continue, a run in `checking` repeats its check, and a watcher resumes a run whose session stays idle. The watcher also moves a run from `queued` to `running` when its container gets a slot and fails it when the workspace cannot be prepared.

## Consequences

- No computer of the user has to be online from the label to the pull request.
- The quality gate is as good as the check command; a project without one gets a pull request after the first turn that changed files.
- The check runs after the push, so the branch can hold a commit that fails the check until the next attempt fixes it; the pull request is opened only after a passing check, or as a draft.
- A run holds a container slot for its whole duration, including checks. The slot limit of [ADR 0016](0016-remote-execution-in-containers.md) queues further runs.
- Creating a webhook needs admin rights on the repository. When GitHub refuses, the settings are still saved, the reason is shown and manual runs work.
- Commits, comments and pull requests are made with the user's token and appear under the user's name.

## Rejected alternatives

- **Letting the agent run the check as a tool and report the result.** One prompt less to build, but the result would rest on the model's claim; a failing project could still produce a "green" pull request.
- **A GitHub App with its own identity.** Cleaner attribution and installation-scoped permissions, but a second authentication model next to the OAuth token that remote execution already uses.
- **Polling issues instead of a webhook.** Works without a public URL, but spends API quota on every project and reacts with a delay.
- **A separate executor for checks.** Would need a second copy of the workspace; the session container already holds the exact tree that was pushed.
