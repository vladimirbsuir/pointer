# 0006. Authentication: email and password plus GitHub OAuth

Status: Accepted

## Context

The backend is multi-user and cloud-hosted, so users need accounts.
GitHub access will also be useful for server-side execution (cloning and pushing repositories).

## Decision

- Two sign-in methods: email and password, and GitHub OAuth. Both resolve to one user account and can be linked.
- The backend issues a short-lived **access token** (JWT) and a long-lived opaque **refresh token** stored hashed in the database, rotated on every use.
- The desktop client stores the refresh token in the Windows Credential Manager.
- GitHub sign-in from the desktop uses the system browser with Authorization Code flow and PKCE. The result returns to the app through a custom URL scheme (`pointer://`).
- The WebSocket connection is authenticated with the access token at handshake and re-authenticated on refresh.

## Consequences

- Email sign-in works without a GitHub account.
- The GitHub identity is available later for repository access, with extra scopes requested only when needed.
- Refresh-token rotation allows detecting token reuse and revoking sessions.

## Rejected alternatives

- **Only GitHub OAuth:** simpler, but forces a GitHub account on every user.
- **Server-side sessions with cookies:** awkward for a desktop client and a WebSocket connection.
