# 17. Replay time moves with the investigation

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

Replay answered every query as of the moment the alert fired. A decoy that recovers seconds later, such as `decoy-catalog-blip`, therefore looked broken for the whole investigation. Its correct answer, "it recovered, take no action", could never be reached from the evidence. The evidence validator also re-runs each cited call ([ADR 13](0013-the-investigator-and-its-evidence-ledger.md)), so time must not move under a citation either.

## Decision

- In replay, a tool server's clock is the alert's moment plus `elapsed()` seconds, supplied by the caller. The investigator advances it per step through a hook. The current test uses two minutes per step, enough for a one-minute rate window to clear a short blip.
- Every ledger entry records the step it was made in. The validator re-runs a cited call at that same step, so evidence stays pinned to the moment it was observed.
- Without `elapsed`, the clock stays at the alert's moment, as before.

## Consequences

- Decoys can be concluded from evidence. `decoy-catalog-blip` now has scripted turns that see p95 above 0.3 s when the alert fired and below 0.1 s two minutes later, and conclude `none`. A test shows a frozen clock could not.
- How much time one step represents is a modelling choice. The evaluation harness should fix it per run and report it.
