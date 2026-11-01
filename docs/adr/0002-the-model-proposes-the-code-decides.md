# 2. The model proposes, the code decides

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodríguez Caro

## Context

Guardia's agents read logs, alert annotations, commit messages, trace attributes and runbooks. All of that text is written by people and programs that Guardia does not control, and any of it can carry instructions meant to manipulate a model. Language models cannot reliably tell data from instructions, so a prompt cannot be the only thing standing between an incident and an unsafe change to production. An on-call engineer that restarts, rolls back or fails over the wrong thing does more damage than the incident it was called for.

## Decision

- Agents only reach read-only tools. No agent runs a command, writes to a system or changes infrastructure.
- A state-changing action exists only as a typed proposal, created through `mcp-actions`, whose single tool returns a proposal ID and executes nothing.
- A policy engine, written in Java and covered by tests written before it, decides each proposal. It checks the versioned action catalog, the preconditions, the blast radius computed from the service dependency graph, the rate limits, the kill switch and the approval rule for the action's risk tier, and only then runs the action, with an idempotency key and a dry run first.
- Every proposal, decision and action is written to an append-only, hash-chained audit log.
- Text from the observed system is data. It is wrapped in delimiters before a model sees it, and known injection patterns are flagged.
- The code is split by responsibility into a monorepo: `mercadillo/` (the observed system), `chaos/`, `mcp/` (tool servers), `agents/`, `policy/`, `console/`, `evals/`, `docs/` and `deploy/`. The policy engine is a separate service in a separate language from the agents, so nothing in the agents' process can call its execution path directly.

## Consequences

- A successful prompt injection can at most produce a proposal, and the policy engine judges that proposal like any other. The evaluation bank measures how often injections succeed in steering the diagnosis, and a successful one fails the build.
- Safety can be tested deterministically by sending proposals straight to the policy engine, with no model involved.
- Each new kind of action needs a catalog entry, preconditions, a verification query and a rollback before any agent can propose it. This is slower than letting an agent run a shell, and it is the point.
- The two-language split adds a network hop and a contract to keep in sync between the agents and the policy engine.
