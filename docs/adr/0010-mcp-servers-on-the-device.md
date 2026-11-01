# 0010. MCP servers run on the user's device

Status: Accepted.

## Context

TOOL-12 asks for tools from MCP servers configured by the user.
Most MCP servers are local processes started over stdio (`npx`, `uvx`, a binary) that need the user's machine: its files, its credentials, its network. Some are remote services reached over streamable HTTP.
Server configuration usually contains machine-specific paths and secrets (API tokens in environment variables or headers).
The agent loop runs on the backend ([ADR 0001](0001-server-side-agent-orchestration.md)) and already sends every tool call to the session's device through `exec.request`.

## Decision

- The desktop core is the MCP client. It supports the stdio transport and streamable HTTP, both speaking MCP JSON-RPC (`initialize`, `tools/list`, `tools/call`).
- Server configuration lives only on the device, in `mcp.json` in the app data directory, and is edited in the desktop settings. Secrets in it never reach the backend.
- After connecting, and whenever the set of tools changes, the core sends `device.tools` with the tools of all running servers. The backend stores them as tool definitions with source `mcp`, owned by the user, named `mcp__<server>__<tool>`, so permission policies and session grants work as for built-in tools.
- A session offers the MCP tools reported by its executor device. Tools with `readOnlyHint` are offered in every mode, others only in Agent mode. All MCP tools default to `ask`, because hints come from the server and are not trusted.
- A call goes through the normal executor path as operation `mcp.call` with `server`, `tool` and `arguments`. The approval preview shows the server, the tool and the arguments.
- On Windows, stdio commands are resolved through `PATH` and `PATHEXT`, so `npx` and other `.cmd` shims work without the user writing `cmd /c`.

## Consequences

- Any MCP server that works in other desktop clients works the same way here, with the same configuration shape.
- MCP tools are available only while the device that runs them is connected, like every other tool of the session.
- A remote HTTP server could in principle be called from the backend directly, but going through the device keeps one code path and keeps its credentials local. Remote execution (M6) can revisit this.
- The backend never sees server commands, environment variables or headers, only tool names, descriptions and schemas.

## Rejected alternatives

- **MCP client on the backend.** Cannot start local stdio servers, and would require uploading secrets.
- **Configuration stored on the backend and synced to devices.** Commands and paths differ per machine, and the secrets would leave the device.
