# Feature specs

Approved features beyond the roadmap. [05-roadmap.md](../05-roadmap.md) stays the baseline plan and is not extended; new work is specified here.

Each spec is a technical brief for implementation: what is built, where it plugs into the existing code, data model, API, phases, tests and the numbers it produces for the thesis. Prose is kept to the minimum needed to implement without re-deriving the design.

## Index

| # | Spec | Requirement ids | Status |
|---|---|---|---|
| F1 | [Issue to pull request automation](f1-issue-to-pr.md) | AUTO-1 to AUTO-6 | Approved |
| F2 | [Hybrid code search and RAG benchmark](f2-hybrid-search.md) | CTX-8 to CTX-10 | In progress |
| F3 | [Smart model routing](f3-model-routing.md) | ROUTE-1 to ROUTE-6 | Approved |
| F4 | [Agent memory](f4-agent-memory.md) | MEM-1 to MEM-7 | Approved |

Status values: `Approved` (agreed, not started), `In progress`, `Implemented`, `Dropped`.

## Rules

- Requirement ids are reserved here and copied into [01-requirements.md](../01-requirements.md) when implementation starts; they stay stable afterwards.
- A spec that changes an architectural decision gets an ADR in `docs/adr/` when implementation starts; the spec links it.
- Protocol changes go to [03-protocol.md](../03-protocol.md) and schema changes to [04-data-model.md](../04-data-model.md) in the same commit as the code.
- When a phase is implemented, mark it in the spec's Phases section and update the status above.
- Migration numbers in specs are placeholders (`Vn`); take the next free number at implementation time.

## Spec template

```
# Fn. Title
Status · Requirement ids · Depends on
## Goal                 two or three sentences
## Builds on            existing modules, ADRs, files
## Design               data model, backend, API/protocol, desktop, core/runner, Telegram
## Phases               ordered, each shippable
## Tests                what is covered and how
## Thesis metrics       what is measured and how
## Open questions       only real ones
```
