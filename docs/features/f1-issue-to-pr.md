# F1. Issue to pull request automation

Status: Approved · Requirement ids: AUTO-1 to AUTO-6 · Depends on: remote execution (REM-3, [ADR 0016](../adr/0016-remote-execution-in-containers.md)), Telegram (REM-1, 2, [ADR 0015](../adr/0015-telegram-remote-control.md)); budget limit from [F3](f3-model-routing.md) is used when present.

## Goal

A GitHub issue with a trigger label becomes a server session that implements it, verifies the result with the project's own check command, retries on failure and opens a pull request that closes the issue. Progress goes to the issue as comments and to Telegram. No computer of the user has to be online.

## Builds on

- `container/RemoteSessionService`: `create`, per-turn `publish` (commit and push), runner slots, `queued` status, idle stop.
- `container/GitHubRepositories` / `RestGitHubRepositories`: GitHub API with the user's `repo` token (`UserService.GitHubCredentials`).
- `agent/AgentRunner.send` and `finishTurn`; `session/SessionEventBus` listeners (as `telegram/TelegramNotifier`).
- Projects already store `repository` (`owner/name`, V17).
- Public backend URL from deployment ([ADR 0017](../adr/0017-server-deployment.md)) for webhooks.

## Requirements

| Id | Requirement |
|---|---|
| AUTO-1 | A project with a repository can enable an automation: trigger label, base branch, model, check command, max fix attempts, optional budget. |
| AUTO-2 | Labelling an issue with the trigger label starts a server session; a manual "Run on issue #N" from the desktop or Telegram `/issue N` does the same. |
| AUTO-3 | The session prompt is built from the issue title, body and comments; the agent runs in Agent mode with auto-approve. |
| AUTO-4 | After each turn that changed files the backend runs the check command in the container; on failure the output is sent back to the agent as a follow-up message, up to the attempt limit. |
| AUTO-5 | On success a pull request `Closes #N` is opened; on final failure a draft pull request with the failing output is opened. Status is commented on the issue. |
| AUTO-6 | Runs are listed per project with status, attempts, cost, duration and links; Telegram notifies on start, success and failure. |

## Design

### Data model (`Vn__automations.sql`)

```sql
CREATE TABLE automations (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id       uuid NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    enabled          boolean NOT NULL DEFAULT true,
    trigger_label    text NOT NULL DEFAULT 'pointer',
    base_branch      text,
    model_id         uuid REFERENCES models (id) ON DELETE SET NULL,
    check_command    text,
    max_attempts     integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    budget_usd       numeric(10, 4),
    webhook_id       bigint,
    webhook_secret_encrypted bytea NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

CREATE TABLE automation_runs (
    id                uuid PRIMARY KEY,
    automation_id     uuid NOT NULL REFERENCES automations (id) ON DELETE CASCADE,
    session_id        uuid REFERENCES sessions (id) ON DELETE SET NULL,
    issue_number      integer NOT NULL,
    issue_title       text NOT NULL,
    trigger           text NOT NULL CHECK (trigger IN ('label', 'desktop', 'telegram')),
    status            text NOT NULL CHECK (status IN ('queued', 'running', 'checking', 'succeeded', 'failed', 'cancelled')),
    attempts          integer NOT NULL DEFAULT 0,
    last_check_output text,
    pull_request_url  text,
    status_comment_id bigint,
    cost_usd          numeric(12, 6) NOT NULL DEFAULT 0,
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz
);

CREATE UNIQUE INDEX automation_runs_active_issue_idx
    ON automation_runs (automation_id, issue_number)
    WHERE status IN ('queued', 'running', 'checking');
```

The partial unique index makes a repeated label event for an issue with an active run a no-op.

### Backend: new module `automation`

| Class | Responsibility |
|---|---|
| `AutomationController` | CRUD `/api/v1/projects/{projectId}/automation`, `POST .../automation/runs` (manual run by issue number), `GET .../automation/runs`, `POST .../runs/{runId}/cancel`. |
| `GitHubWebhookController` | `POST /api/v1/webhooks/github/{automationId}`, unauthenticated, verifies `X-Hub-Signature-256` (HMAC-SHA256 with the decrypted secret, constant-time compare). Handles `issues` with `action=labeled` and the trigger label; ignores everything else with 204. |
| `AutomationService` | Enable/disable: creates or deletes the repository webhook through the GitHub API (`POST /repos/{owner}/{repo}/hooks`, events `issues`, content type JSON). Starts runs. |
| `AutomationRunner` | Implements `SessionEventListener`; drives the run state machine below. |
| `IssuePrompt` | Builds the first user message from the issue (title, body, up to 20 comments, truncated to 12,000 characters) plus a fixed instruction: implement, keep changes minimal, run tests, do not touch CI config unless asked. |
| `GitHubRepositories` (extended) | `issue`, `issueComments`, `createIssueComment`, `updateIssueComment`, `createHook`, `deleteHook`, `createPullRequest(draft, body)`. |

Webhook secret: 32 random bytes, encrypted with `common/crypto` like provider keys. The webhook URL uses a new property `pointer.public-url`; without it the label trigger is disabled and only manual runs work (local development).

### Run state machine

```
queued ──slot──▶ running ──turn finished, files changed──▶ checking
                    ▲                                        │
                    │ check failed, attempts < max           │ check passed
                    └──── follow-up message with output ◀────┤
                                                             ▼
                         failed (draft PR) ◀── attempts = max or budget hit      succeeded (PR)
```

1. Create the run, then the server session through `RemoteSessionService.create` (title `#N <issue title>`, mode `agent`, auto-approve, the automation's model, branch `pointer/issue-<N>-<slug>`). Status `queued` until the workspace leaves `queued`.
2. Post a status comment on the issue and keep its id; later states edit the same comment instead of adding new ones.
3. Send the issue prompt with `AgentRunner.send`.
4. On `turn.finished`: if the turn did not change files and the agent asked a question, post the question on the issue and mark the run `failed` with reason `needs_input` (no back-and-forth through issues in this version). Otherwise wait for `publish` to finish, then run the check.
5. Check: an executor `run_command` request issued by the backend (not by the model) with the check command, 15 minutes timeout. No check command means success after the first turn.
6. Failure: `attempts++`; if below max, send a follow-up message: "The check command `<cmd>` failed with exit code X. Output (last 200 lines): ... Fix the cause." Back to step 4.
7. Success: create the pull request (title = issue title, body = agent's final message, run summary table, `Closes #N`). Final failure: draft pull request with the last check output. Update the status comment and notify Telegram.
8. Budget: if the session cost passes `budget_usd`, cancel the turn and finish as `failed` with reason `budget_exceeded`.

Recovery after a backend restart: runs in `running`/`checking` whose session turn is `interrupted` are resumed from step 4 by a startup task.

### Protocol and events

- New session event `automation.updated` `{runId, status, attempts, pullRequestUrl}` on the run's session; the desktop also polls the run list per project.
- Document the webhook endpoint and the REST endpoints in [03-protocol.md](../03-protocol.md).

### Desktop

- Project settings → Automation: toggle, label, base branch, model picker, check command, attempts, budget; webhook state (created / missing / no public URL).
- Project view → Runs tab: table of runs (issue link, status, attempts, cost, duration, PR link), opening a run opens its session.
- Session header for automation sessions: issue link and attempt counter.

### Telegram

- `/issue <number>` in a project context starts a manual run.
- Notifications: run started, check failed (attempt k of n), succeeded with the PR link, failed with reason.

## Phases

1. Manual runs from the desktop: tables, `AutomationRunner` with check-and-retry, PR creation, runs list. Works locally without a public URL.
2. Label trigger: webhook creation, signature check, `pointer.public-url`.
3. Issue status comment, Telegram `/issue` and notifications.
4. Budget limit and restart recovery.

## Tests

- `GitHubWebhookController`: valid and invalid signatures, unrelated events, duplicate deliveries (partial index).
- `AutomationRunner` with a fake `ContainerRuntime`, a scripted fake LLM and a fake executor: pass on first try, pass on second attempt, exhausted attempts → draft PR, no-change turn, budget hit, cancellation.
- `IssuePrompt` truncation.
- `RestGitHubRepositories` against a mock server for the new endpoints.
- Manual end-to-end on the VPS against a test repository.

## Thesis metrics

- Success rate on a fixed set of 10 to 15 small issues in a test repository (bug fixes, small features, tests), per model.
- Attempts per successful run, distribution of failure reasons.
- Median duration and cost per run, split into container start, agent turns, checks.

## Open questions

- Whether the `repo` scope is enough to create repository webhooks for all repositories the user can access, or `admin:repo_hook` must be requested; verify on the first implementation.
- Issue comments from other users as follow-up messages (conversation through the issue) are out of this version.
