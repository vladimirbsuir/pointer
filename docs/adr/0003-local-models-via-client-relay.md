# 0003. Local models through a client relay

Status: Accepted

## Context

Local model runtimes such as Ollama listen on `localhost` of the user's machine. A cloud backend cannot reach them.

Options considered:

- **A. Client relay.** For providers marked local, the backend sends the HTTP request to the desktop client over the existing WebSocket. The client performs it against localhost and streams the response back.
- **B. User-managed tunnel.** The user exposes the local runtime to the internet and configures a public URL.
- **C. No local models** in the MVP.

## Decision

Option A. A provider has a `local` flag. Requests to local providers go through a relay channel of the client connection instead of a direct HTTP call.
The relay is transparent to the agent loop: it is another transport behind the provider interface.

## Consequences

- Local models work with no network setup and without exposing the user's machine.
- A local provider is usable only while a desktop client of that user is connected.
- The client must restrict relay targets to configured local providers, so the channel cannot be abused to reach arbitrary hosts on the user's network.
- Streaming responses travel backend ← client ← runtime, adding a hop. Negligible compared to local inference speed.

## Rejected alternatives

- **B** is fragile, insecure for most users and shifts setup work onto them.
- **C** contradicts the bring-your-own-model principle.
