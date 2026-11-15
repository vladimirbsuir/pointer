# 0022. Explore sub-agent

Status: Accepted.

## Context

Reading and searching a project fills the main model's context with file contents and search output that are needed only to reach a conclusion, and every later step pays for them again on the most expensive model. [ADR 0020](0020-model-roles-and-usage-ledger.md) rejected routing individual agent steps by heuristics and named a sub-agent as the alternative. Feature spec: [F3](../features/f3-model-routing.md) (ROUTE-4).

## Decision

- **A server-side `explore` tool.** Whenever the session has file tools, the main model gets `explore {task, paths?}`. The agent loop handles it like `propose_plan`: nothing is sent to the device for the tool itself.
- **The model is a setting, not a routing mode.** The sub-agent runs on the exploration model from user settings when it is set and supports tools, otherwise on the fast model when the session uses smart routing, otherwise on the session model. Delegation keeps the main context small even on one model; a cheaper model additionally moves the cost. The first version offered the tool only with smart routing and a fast model.
- **A nested loop with a fresh context.** `agent/ExploreAgent` runs that model with its own system prompt and only the task; it does not see the conversation. It may call `list_directory`, `read_file`, `glob`, `grep` and `semantic_search`; MCP, custom, memory, web and write tools are refused. Its calls go through the normal tool handler, so permissions, approvals and the executing device are the same as for the main agent.
- **Bounded.** At most 15 model calls; the last one, or the first after the context passes 60 % of the model's window, has no tools and asks for the final report. The report is cut to 8,000 characters and becomes the tool result.
- **Nested items.** The sub-agent's tool calls are items with `parent_item_id` set to the `explore` item. They are excluded from the main model's context, compaction, memory curation and Telegram progress, and the desktop shows them inside the collapsed `explore` card.
- **Failure stays local.** A model error in the sub-agent fails the `explore` tool call, not the turn; the main model reads the error and continues on its own. Cancelling the turn cancels the sub-agent.
- **Accounting.** Sub-agent calls are recorded in `model_usage` with role and purpose `explore` and added to the session cost, so the budget counts them; they do not change the session's context size.

## Consequences

- The main model's context grows by the task and the report instead of every file read; with a cheaper exploration model the cost of exploration moves to it. The sub-agent resends its own growing context on every turn, so total tokens and duration rise; [benchmarks/routing.md](../benchmarks/routing.md) has the numbers.
- The report is a summary by a weaker model, so the tool description tells the main model to read the exact lines before editing.
- The main model decides when to delegate.

## Rejected alternatives

- **Storing the sub-agent's messages in the payload of the `explore` item.** No schema change, but approvals, live status and file previews of its tool calls would need a second implementation.
- **Letting the sub-agent use every read-kind tool.** Memory and web tools have side effects or reach outside the project, and their results belong in the main conversation.
- **Parallel sub-agents.** Useful for large surveys, but tool execution on one device is sequential today.
