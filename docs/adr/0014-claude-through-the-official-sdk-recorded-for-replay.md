# 14. Claude through the official SDK, recorded for replay

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The investigator needs a real model to measure anything, and the brief asks for real runs behind an environment variable and a hard spend cap, recorded so that CI and any clone can replay them for free ([ADR 13](0013-the-investigator-and-its-evidence-ledger.md)).

## Decision

- The real provider uses the official Anthropic TypeScript SDK (`@anthropic-ai/sdk` 1.3.0) with `claude-opus-5-5` at effort `high`. It runs only with `GUARDIA_MODEL_PROVIDER=anthropic`, always wrapped in the spend cap from `GUARDIA_MAX_SPEND_USD`, priced at Opus 5.5's list rates ($4 and $20 per million tokens).
- The model sees the read-only tools, with their names translated from `metrics.query_metrics` to `metrics__query_metrics` because the API does not accept dots. It ends by calling a `submit_diagnosis` tool whose input schema is the diagnosis schema. Tool choice stays `auto`, since Opus 5.5 refuses forced tool use.
- A manual loop is used instead of the SDK's tool runner, because every tool call must pass through the evidence ledger and its limits.
- Every turn is recorded to `agents/responses/<scenario>.json` with the provenance "Recorded from claude-opus-5-5 on <date>". The fake provider replays that file in CI.
- `make investigate SCENARIO=<id>` runs the fake provider by default. With the variable set, it asks Claude and records the turns.

## Consequences

- Tests drive the provider against a local stand-in for the Messages API. They check the model ID, that tool names are valid, that no forced tool choice is sent, and that each tool result is matched to its call. They also check that a recording replays to the same diagnosis. No key is used.
- No call to the real API has been made from this repository yet. Its first run will be the first measurement of a model, and it needs the owner's key and spending decision.
- The spend cap counts input and output tokens at list price. It ignores prompt-cache discounts, so it overestimates spend, which errs on the safe side.
