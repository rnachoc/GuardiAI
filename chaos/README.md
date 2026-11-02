# Chaos

`guardia-chaos` and GuardiAI's incident scenarios ([ADR 7](../docs/adr/0007-scenarios-as-validated-yaml-in-typescript.md)).

**Status: in progress (Phase 2).**

What exists:

- The scenario format: a strict schema in [`src/scenario.ts`](src/scenario.ts) covering faults, the alert raised, and the ground truth (root cause, acceptable and forbidden actions, evidence queries).
- 17 scenarios in [`scenarios/`](scenarios): 11 incidents, 3 decoys and 3 prompt injections, across 4 families.
- `guardia-chaos validate`, and tests that each rule rejects what it should.
- The injector ([ADR 8](../docs/adr/0008-faults-through-toxiproxy-and-the-docker-api.md)): `make chaos SCENARIO=<id>` applies and reverts latency, timeout, kill, pause and planted-log faults, and writes a run record to `runs/`. `make chaos-reset` removes any network fault left behind.

- The snapshot recorder ([ADR 10](../docs/adr/0010-fixture-bundles-with-a-sealed-ground-truth.md)). `make record SCENARIO=<id>` runs a scenario and stores what an engineer could see in `fixtures/<id>/`, in canonical, hashed files: metrics, logs, traces, changes and the alert. What only the grader may see goes under `truth/`. `make record-all` records every scenario. On every build, `make test` verifies each bundle: its hashes, its byte-for-byte replay, its ground truth and that nothing leaks.

What does not exist yet:
- The other 30 scenarios and 4 families.

All 17 scenarios were run on the live stack, and their evidence held, after the fixes listed in [PROGRESS.md](../PROGRESS.md), which also shows what was measured.

```bash
cd chaos && npm ci && npm test && node src/cli.ts validate
```
