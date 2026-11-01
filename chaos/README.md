# Chaos

`guardia-chaos` and Guardia's incident scenarios ([ADR 7](../docs/adr/0007-scenarios-as-validated-yaml-in-typescript.md)).

**Status: in progress (Phase 2).**

What exists:

- The scenario format: a strict schema in [`src/scenario.ts`](src/scenario.ts) covering faults, the alert raised, and the ground truth (root cause, acceptable and forbidden actions, evidence queries).
- 10 scenarios in [`scenarios/`](scenarios): 6 incidents, 2 decoys and 2 prompt injections, across 4 families.
- `guardia-chaos validate`, and tests that each rule rejects what it should.

What does not exist yet:

- The fault injector: Toxiproxy on four connections, plus container kill, container pause and planted logs.
- The snapshot recorder and its replayable fixture bundles.
- The other 30 scenarios and 4 families.

The evidence queries are checked for shape only. None has been run against a recording of its scenario yet.

```bash
cd chaos && npm ci && npm test && node src/cli.ts validate
```
