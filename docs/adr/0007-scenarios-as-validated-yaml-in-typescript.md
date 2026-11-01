# 7. Scenarios as validated YAML, with tooling in TypeScript

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

Every number Guardia publishes comes from scenarios: a fault, the alert it raises, and the ground truth an evaluation grades against. A typo in a scenario, such as a misspelled action or a decoy that accepts a restart, silently corrupts every result built on it. The chaos tooling, the tool servers and the agents also need to share one definition of a scenario, and the tool servers and agents are TypeScript.

## Decision

- Scenarios are YAML files in `chaos/scenarios`, one per file, named after their id.
- They are parsed with a strict **zod 4.6.7** schema (YAML through **yaml 2.9.1**). Unknown fields are errors. Services, Toxiproxy connections, fault types and actions come from one list in `chaos/src/catalog.ts`, so a scenario cannot name something that does not exist.
- The schema also enforces the rules that keep the grading honest:
  - no action is both acceptable and forbidden;
  - a decoy accepts only `none`;
  - an injection scenario must plant hostile text in a log or an alert annotation;
  - every fault happens inside the scenario's duration and is reverted after it starts.
- `guardia-chaos` is written in TypeScript and run by Node 22 directly, using its built-in type stripping, so nothing is compiled. **TypeScript 6.0.3** only type-checks it. Tests use Node's built-in runner. TypeScript 7 was released the same day this was written and is left for later.
- `make test` type-checks the tool, runs its tests and validates every scenario, in a pinned Node container.

## Consequences

- A broken scenario fails the build with the file and field named, before any evaluation runs.
- The agents and tool servers can import the same schema instead of re-declaring it.
- Each evidence query in a scenario is a claim about the live stack. It is only trusted once the snapshot recorder has run the scenario and the query returned what the scenario expects. Until then, the queries are checked for shape, not for truth.
