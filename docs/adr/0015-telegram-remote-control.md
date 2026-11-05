# 0015. Telegram remote control

Status: Accepted.

## Context

REM-1 and REM-2 ask that a user can link a Telegram account and, through a bot, list sessions, send messages, receive progress and final answers, and approve or deny tool calls.
Sessions already live on the backend and every client action is a call to an application service ([ADR 0001](0001-server-side-agent-orchestration.md), [ADR 0007](0007-communication-model.md)), so the bot can be another client without changes to the agent loop.

The open questions were: how the backend receives Telegram updates, how an account is linked, which events reach the chat and in what form, and how approvals work from a phone.

## Decision

- **One bot per deployment, configured on the server.** The token comes from `POINTER_TELEGRAM_BOT_TOKEN`. Without a token the module stays inactive and the desktop shows that the bot is not configured.
- **Long polling.** The `telegram` module calls `getUpdates` with a 30 second timeout on a virtual thread. It works on a developer machine and behind NAT without a public URL or TLS. The Bot API is called through Spring's `RestClient`; no third-party bot library.
- **Linking through a one-time deep link.** `POST /telegram/link` returns `https://t.me/<bot>?start=<code>`. The code is 24 random bytes, stored only as a SHA-256 hash, valid for 10 minutes and single use. `/start <code>` in a private chat links the chat to the account. A user has at most one chat and a chat belongs to at most one user; linking again replaces the old link. Unlinking is possible from the desktop settings and with `/unlink`.
- **The bot is a client of the existing services.** Commands call `SessionService`, `AgentRunner`, `ApprovalService` and `ProjectService` with the linked user's id, so ownership checks and validation are the same as for REST. A message from Telegram uses the update id as idempotency key.
- **Events reach the bot through `SessionEventListener`.** The session event log notifies listeners after commit, next to the WebSocket fan-out. The Telegram listener only queues work, so the agent loop never waits for Telegram.
- **One worker thread.** Incoming updates, notifications and throttled progress edits run on a single worker, so the module's in-memory state needs no locking and messages keep their order. Volumes are small; Telegram's rate limits are lower than what one worker can send.
- **Notification policy.** The chat has a selected session. For it, every message turn gets one progress message that is edited in place at most every 1.5 seconds with the tool calls and their status, followed by the final answer. For all sessions of the user, approval requests, plans waiting for review, final answers and failures are sent; answers from other sessions carry an "Open session" button that selects that session.
- **Approvals with inline buttons.** Approve, Allow for session and Deny; proposals of custom tools get only Approve and Deny, with the default tool options. Replying to an approval message with text denies it with that message. When an approval is resolved anywhere, the message is edited to show the decision and its buttons are removed. Plans get an Approve plan button; rejecting a plan is a new message, as in the desktop.
- **New sessions from the chat.** `/new` lists projects; the session is created on an online device that has the project mapped, because tools run on the device.
- **Formatting.** Answers are converted from Markdown to Telegram HTML (code blocks, inline code, bold, links, headings, lists) and split into parts below the message limit, reopening code blocks across parts. If Telegram rejects the markup, the text is sent plain. Bot texts follow the account locale (English or Russian).

## Consequences

- Remote control works as soon as a token is set, also for a backend on a laptop.
- Pending approvals and progress messages are kept in memory. After a backend restart, running turns are interrupted anyway, and buttons of old approvals answer that the request is no longer pending.
- Long polling ties update handling to a single backend instance, which matches the deployment ([ADR 0005](0005-backend-stack.md)).
- Images, files and voice messages are not supported; the bot answers that only text is.
- Final answers from every session also arrive while the user works at the desktop. A mute setting can be added if this turns out to be noisy.

## Rejected alternatives

- **Webhook.** Needs a public HTTPS endpoint, which a local backend does not have. It can be added in M7 behind the same handler.
- **A bot library (TelegramBots, java-telegram-bot-api).** Large dependencies for five API methods, and harder to point at a fake server in tests.
- **A bot per user (users bring their own token).** More setup for the user and a poller per token, with no benefit for a single deployment.
- **Linking by typing a code shown in the desktop.** A deep link is one click and carries the code itself.
- **A separate message per tool call.** Floods the chat during long turns.
