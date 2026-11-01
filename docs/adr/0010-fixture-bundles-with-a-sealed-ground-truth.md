# 10. Fixture bundles with a sealed ground truth

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

Evaluations must be cheap, deterministic and runnable without the live stack, so every scenario is recorded once and replayed many times. A recording that silently changes, or one that lets the agents see the answer, would make every result built on it worthless. The brief also asks that evidence be re-checked by re-running stored queries.

## Decision

- `make record SCENARIO=<id>` runs a scenario and then captures the following into `chaos/fixtures/<id>/`:
  - the raw series of a fixed list of metrics, with labels that change on every restart dropped;
  - every warning and error log line, plus a sample of the other lines from each service;
  - up to 40 traces, failed first, then slow, then normal, with each span trimmed to what an investigation uses;
  - the change events;
  - the alert.
- Those are the **tool-facing** files. What only the grader may see goes under `truth/`: the fault timeline and each piece of evidence measured at its instant. The tool servers will serve the first set and never the second.
- Every evidence item in a scenario carries a machine-checkable assertion: at mid-fault or at the end, a comparison with a number. The recorder measures each one, and a recording whose ground truth does not hold fails.
- Each file is canonical JSON, with keys sorted and numbers kept to six significant digits, gzip-compressed without a timestamp. `manifest.json` lists every file's SHA-256.
- `make test` checks every scenario's recording on every CI run. It verifies the hashes, checks that each file re-serialises to the same bytes, re-checks every assertion against the recorded measurement, and checks that no tool-facing file mentions what only the ground truth knows.

## Consequences

- A bundle replays byte for byte, and any edit is detected.
- The ground truth of every scenario is demonstrated by a recording, not just claimed by its author.
- Bundles live in git, a few hundred kilobytes each, compressed. Recording all scenarios takes about an hour of wall-clock time on the live stack.
- Replay serves what was recorded and nothing else. The tool servers will answer queries over the recorded series, so a query about a metric outside the list has no data in replay.
