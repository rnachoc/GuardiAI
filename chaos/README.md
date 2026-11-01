# Chaos

`guardia-chaos` and Guardia's incident scenarios ([ADR 7](../docs/adr/0007-scenarios-as-validated-yaml-in-typescript.md)).

**Status: in progress (Phase 2).**

What exists:

- The scenario format: a strict schema in [`src/scenario.ts`](src/scenario.ts) covering faults, the alert raised, and the ground truth (root cause, acceptable and forbidden actions, evidence queries).
- 10 scenarios in [`scenarios/`](scenarios): 6 incidents, 2 decoys and 2 prompt injections, across 4 families.
- `guardia-chaos validate`, and tests that each rule rejects what it should.
- The injector ([ADR 8](../docs/adr/0008-faults-through-toxiproxy-and-the-docker-api.md)): `make chaos SCENARIO=<id>` applies and reverts latency, timeout, kill, pause and planted-log faults, and writes a run record to `runs/`. `make chaos-reset` removes any network fault left behind.

What does not exist yet:

- The snapshot recorder and its replayable fixture bundles.
- The other 30 scenarios and 4 families.

Four scenarios (`payments-latency`, `payments-timeouts`, `inventory-killed`, `decoy-notifier-hiccup`) were run on the live stack, and their evidence held, after the fixes listed in [PROGRESS.md](../PROGRESS.md). The other six have only been checked for shape.

```bash
cd chaos && npm ci && npm test && node src/cli.ts validate
```
