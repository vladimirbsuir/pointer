# 0014. Agent-proposed custom tools

Status: Accepted.

## Context

TOOL-13 asks that the agent can propose a custom tool which, after the user approves it, is stored per user or per project and is available in later sessions.
Project files and toolchains exist only on the device ([ADR 0001](0001-server-side-agent-orchestration.md)), every tool call already goes through the executor with permission policies and approvals, and the registry already holds tools of several sources (`builtin`, `mcp`).
`ServerOperation` ([ADR 0013](0013-semantic-code-index.md)) lets the backend execute an operation itself, but it cannot run code that needs the project folder, and the backend must not run code written by a model.

The open questions were: what an implementation is, where it runs, where it is stored, how proposals are approved, how individual calls are governed, and how changes to a tool are versioned.

## Decision

- **An implementation is a script.** A custom tool consists of a name, a description, a JSON Schema of its arguments, an interpreter and a script body. Interpreters come from a fixed list: `python`, `node`, `powershell`, `bash`. The script receives its arguments as a JSON object on stdin and writes its result to stdout. Arguments are never substituted into a command line.
- **It runs on the device.** The executor operation `custom_tool.run` carries `interpreter`, `body` and `arguments`. The core writes the body to a temporary file and runs it with the resolved interpreter through the same process machinery as `run_command`: the project root (or the session worktree) as the working directory, a Job Object, a timeout, cancellation and streamed output. The device stores nothing; the backend sends the approved body with every call.
- **Interpreters are reported by the device.** The core probes the interpreters once at start and sends them in `hello` as `interpreters`. A session offers a custom tool only if its executor device reports the tool's interpreter, and offers `propose_tool` only if it reports at least one; the tool description lists the available ones.
- **Proposals are tool calls that wait for approval.** In Agent mode the model gets a server-side tool `propose_tool` (`name`, `description`, `parameters`, `interpreter`, `body`, `scope`). It is not part of the registry and never reaches the executor. The call becomes a `tool_call` item with an approval of preview kind `tool_proposal`, and the turn waits as for any other approval. Session auto-approve and session grants never apply to proposals. Approving stores the tool and the turn continues; the tool list is rebuilt before every model call, so the agent can use the new tool in the next step.
- **The user decides how calls are governed.** The approval card shows the full script, the schema and, for a new version, the previous version. The user sets the scope (`user` or `project`), the default policy (`ask` by default, or `allow`) and the modes (Agent by default; Ask and Plan can be added). A denial with a message is returned to the model like any denied call. Calls of a custom tool are then evaluated like every other tool call, with an approval preview of kind `custom_tool` showing the tool, its version and the arguments.
- **Storage in the backend database.** The `tools` row (source `custom`, operation `custom_tool.run`, kind `execute`) holds the current version's description, schema and interpreter, so the registry and permission evaluation work unchanged. `custom_tool_versions` holds every approved version with its body and the session that proposed it. Rejected proposals are kept only in the session history.
- **Versioning.** Proposing a tool with the name of a custom tool the session can see is a proposal of a new version of that tool, in its existing scope. Every version is approved separately. The user can roll back to an earlier version, disable or delete a tool in the settings. Changing the current version revokes the session grants of the tool. A tool call item records the version it executed.
- **Names.** `^[a-z][a-z0-9_]{2,47}$`, not used by a built-in or MCP tool, and not used by another custom tool visible in the project, so a project tool never shadows a user tool or the other way round.

## Consequences

- The agent can turn repeated multi-step work (querying a schema, running a filtered test suite, converting a file format) into a single call, and the tool survives the session.
- A custom tool has the same power as `run_command`; there is no sandbox. Safety rests on the user reading the script, on proposals never being auto-approved, on `ask` as the default policy and on calls being tied to an approved version. A prompt injection can make the agent propose a harmful script, which is why the full body is always shown.
- User-scoped tools must be written to work in any project; nothing checks that.
- A script body travels with every call. Bodies are limited to 64 KiB, so this is negligible next to file contents.
- Changes made by a script are side effects like those of `run_command`: they are not recorded as file changes and are not restored by rewind.

## Rejected alternatives

- **Executing on the backend as a `ServerOperation`.** No access to the project, and running model-written code on the shared server is a far larger risk than on the user's own machine.
- **Command templates.** A single command with arguments in environment variables is easier to read, but too weak for real tools; a one-line PowerShell script covers the same case.
- **Composition of existing tools.** Safe, but needs its own language and cannot express most useful tools.
- **An embedded sandboxed runtime (QuickJS or WASM in the core).** Real isolation, but a large amount of Rust work and no access to the project's own toolchain, which is what most useful tools need.
- **Project tools as files in the repository.** Shareable through git, but a cloned repository could carry tools, which would need content-hash based trust and a second storage path. Can be added later as an import.
- **Ending the turn on a proposal, as with plans.** The agent could not use the tool it has just built without the user starting another turn.
