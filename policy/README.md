# Policy and execution service

The code that decides every proposal ([ADR 2](../docs/adr/0002-the-model-proposes-the-code-decides.md), [ADR 15](../docs/adr/0015-a-policy-engine-that-gathers-every-reason.md)).

**Status: in progress (Phase 5).**

What exists, test-first:

- [`catalog-v1.yaml`](src/main/resources/catalog-v1.yaml): the versioned catalog of the nine allowed actions, each with its targets, risk tier, preconditions, hourly limit, maximum blast radius, verification query and auto-revert.
- [`dependency-graph.yaml`](src/main/resources/dependency-graph.yaml), from which each action's blast radius is computed.
- `PolicyEngine.decide`, which rejects with every reason when the kill switch is on, or when the action is outside the catalog, the target is not allowed, a precondition fails or is unknown, the hourly limit is reached, or the blast radius is too large. In suggest mode, every action needs approval; in autonomous mode, only low-risk ones run alone.

What does not exist yet: the hash-chained audit log, the Spring Boot service, `mcp-actions`, idempotent execution, verification and automatic revert.

`make test-policy` runs the tests in a container.
