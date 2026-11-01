# 15. A policy engine that gathers every reason

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The policy engine is the only path from a proposal to a change in the system ([ADR 2](0002-the-model-proposes-the-code-decides.md)). The brief asks for a versioned catalog of actions. Each action has a risk tier, preconditions, a frequency limit, a blast radius computed from the service dependency graph, an approval rule per tier, a verification query and an auto-revert condition. There are two modes, suggest and autonomous, plus a kill switch, and tests are written before the engine.

## Decision

- The catalog is a versioned YAML file read strictly (an unknown field is an error) into Java records. It holds the nine actions of the brief, each with its allowed targets.
- The blast radius of an action is its target plus every service that depends on it, directly or not, from `dependency-graph.yaml`. Restarting inventory affects inventory, checkout and the gateway; restarting catalog affects four services and is over the limit of three.
- `PolicyEngine.decide` gathers **every** reason to reject before answering, rather than stopping at the first, so a person sees all of them. It rejects when:
  - the kill switch is on;
  - the action is not in the catalog;
  - the target is not allowed;
  - a precondition fails or is unknown (an unknown fact counts as a failure);
  - the action already ran its hourly limit;
  - the blast radius is too large.
- Otherwise, in suggest mode, every action needs one person, or two for high risk. In autonomous mode, only a low-risk action whose checks all pass runs without a person.
- Preconditions are facts gathered by code, never asserted by the agent.
- The tests were written before the engine, against the real catalog. One of them requires every action to be usable on at least one target, and it found two entries that could never run, which were fixed.

## Consequences

- Phase 5's acceptance, forbidden and over-blast actions rejected in tests, holds.
- The engine is a plain Java library for now. The Spring Boot service around it, the hash-chained audit log, idempotent execution, verification and automatic revert come next, in Phase 5 and Phase 6.
