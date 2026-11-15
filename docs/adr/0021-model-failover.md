# 0021. Model failover with retry and fallback lists

Status: Accepted.

## Context

A turn failed as soon as one model call failed, even when the cause was temporary (rate limit, overload, an unreachable endpoint) and the user had other providers configured. [ADR 0020](0020-model-roles-and-usage-ledger.md) left fallback lists as an open phase of [F3](../features/f3-model-routing.md) (ROUTE-5).

## Decision

- **Fallback lists per model.** Each model has an ordered list of up to five other models of the same user (`model_fallbacks`). The list belongs to the model, not to the session or the role, so it applies wherever that model is called.
- **One wrapper for every model call.** `routing/RoutedClient` wraps the streaming call for agent steps, compaction, titles and memory curation. With routing `off` it calls the model once, as before.
- **Retry, then switch.** With `smart` routing a call that fails before the first token with HTTP 408, 429, 5xx, an unreachable provider or an offline local provider is retried once on the same model after 2 s (or `Retry-After`, capped at 20 s), then sent to the fallbacks in order. Fallbacks that cannot be resolved or lack a capability the request needs (tools, vision) are skipped. Lists are not chained: a fallback's own list is not followed.
- **Nothing is retried after output started.** Once text, reasoning or a tool call has been streamed, a failure ends the turn, because the client has already shown the partial output.
- **Switches are visible and temporary.** Each switch inside a turn appends a `model_switched` notice item, which context assembly ignores. The turn keeps the working model for its remaining steps; the session model is not changed, and the next turn tries it again. Usage is recorded under the model that answered with role `fallback`.

## Consequences

- A provider outage costs one delay per turn instead of failing the turn, and the user sees which model answered.
- A fallback with a smaller context window can reject a long conversation; that error is not retryable and fails the turn as before.
- Errors reported inside the stream body with HTTP 200 are not classified and are not retried.

## Rejected alternatives

- **Retry and fallback with routing off.** Useful, but routing `off` is defined as the previous behaviour, which regression tests rely on.
- **Changing the session model after a switch.** Simpler stickiness, but it silently changes what the user selected and what later turns cost.
- **Exponential backoff with several retries.** Interactive turns should not wait tens of seconds on a dead provider when another model is available.
