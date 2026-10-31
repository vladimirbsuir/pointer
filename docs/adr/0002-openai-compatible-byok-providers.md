# 0002. Bring-your-own-key OpenAI-compatible providers

Status: Accepted

## Context

Pointer must not depend on a single model vendor or require a paid account with the project.
Most providers and local runtimes (OpenAI, OpenRouter, DeepSeek, Groq, Ollama, LM Studio, vLLM) expose the OpenAI Chat Completions API with tool calling and streaming.

## Decision

Users configure their own providers: base URL, API key, models.
The backend talks to every provider through one OpenAI-compatible client covering `/chat/completions` (streaming, tool calls) and `/models`.

The agent loop depends on an internal provider interface, not on the OpenAI wire format.
Specialized adapters (for example the native Anthropic API, to use features such as prompt caching or extended thinking) can be added later as further implementations.

## Consequences

- One adapter covers the large majority of providers in the MVP.
- Behaviour differences between "compatible" providers (tool-call streaming quirks, missing usage data, context limits) need defensive handling and per-model settings.
- API keys are stored on the backend, encrypted at rest, and never sent back to clients.
- No billing is needed: the user pays providers directly.

## Rejected alternatives

- **Server-managed keys** would require billing and quota management, irrelevant for the project.
- **Vendor SDKs per provider** multiply integration work with little MVP benefit.
