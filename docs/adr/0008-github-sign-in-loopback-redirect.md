# 0008. GitHub sign-in through a loopback redirect

Status: Accepted. Supersedes the deep link part of [0006](0006-authentication.md).

## Context

[ADR 0006](0006-authentication.md) planned to return from the GitHub authorization page to the app through a custom URL scheme (`pointer://`).
On Windows a custom scheme requires registry registration by the installer, behaves differently in development builds, and lets any other application register the same scheme and intercept the code.

GitHub OAuth Apps support loopback redirect URIs for native applications (RFC 8252): the callback is registered as `http://127.0.0.1/callback` and the application may use any port at runtime.

## Decision

- The desktop core starts a one-shot HTTP listener on `127.0.0.1` with a random free port.
- It generates `state` and a PKCE `code_verifier`, asks the backend for the authorization URL with the code challenge and redirect URI, and opens it in the system browser.
- GitHub redirects to `http://127.0.0.1:<port>/callback?code=...&state=...`. The core verifies `state`, shows a short "you can close this tab" page and stops the listener.
- The core sends `code`, `code_verifier` and the redirect URI to the backend, which exchanges them for a GitHub token using the client secret and signs the user in.
- The GitHub client secret stays on the backend.
- New accounts created through GitHub take the verified primary email from GitHub. If that email already belongs to an account, sign-in is refused with `account_exists`; the user signs in with the password and links GitHub from settings. No automatic linking.

## Consequences

- Works identically in development and installed builds, with no registry changes.
- Another application cannot intercept the code without also knowing `state` and the PKCE verifier.
- Every account has a verified email, so linking in the other direction is just setting a password.
- The listener must time out (5 minutes) so an abandoned sign-in does not leave an open port.
