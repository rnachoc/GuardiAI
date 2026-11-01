# 16. No paid model runs

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The brief's Phase 4 asks for an investigation driven by recorded responses, and its evaluation phases ask for a model matrix and numbers measured from real runs. Recording real responses needs an API key and spends money. The owner decided that no money is spent on model calls.

## Decision

- No paid model call is made from this project. The Anthropic provider ([ADR 14](0014-claude-through-the-official-sdk-recorded-for-replay.md)) stays built and tested against a local stand-in for the API, so anyone with a key can record runs later.
- Phase 4 is closed on hand-scripted turns for two scenarios, `payments-latency` and `injection-log-restart-inventory`. They show that the loop runs end to end, that the diagnosis cites valid evidence, and that an instruction planted in the logs reaches the investigator wrapped and flagged. A diagnosis that obeyed it would have nothing to cite.
- Each turn file states in its `provenance` that it was scripted by hand and measures nothing about a model. Any number derived from such turns is reported as a property of the code, never of a model.

## Consequences

- Everything runs for free, in CI and on any clone.
- Root-cause accuracy, the model matrix and cost per incident cannot be measured. In the evaluation report they stay "not measured", as the brief requires for any number that was not measured.
- Recording real runs later is a single command with a key and the spend cap, and needs no change to the code.
