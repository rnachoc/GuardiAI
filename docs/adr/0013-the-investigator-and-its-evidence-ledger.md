# 13. The investigator and its evidence ledger

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The brief's second principle is "evidence or silence": every claim in a diagnosis cites evidence, code rejects a claim whose evidence does not exist, and the stored query is re-run to check that the evidence still supports the claim. A model can invent evidence, cite a call that failed, or describe a result it misread, and none of that may reach a person or the policy engine as fact. Everything must also run without an API key.

## Decision

- The investigator is a bounded loop. A provider returns either tool calls or a final diagnosis, and the loop stops at a step limit, a token budget, a cap on tool calls per step, or the spend cap.
- Every tool call goes through the read-only MCP servers ([ADR 11](0011-read-only-tool-servers-with-one-grammar-for-both-modes.md)) and is written to an **evidence ledger** under an ID (`ev-1`, `ev-2` …) with the SHA-256 of its answer.
- A diagnosis follows a strict schema: up to five hypotheses ranked 1 to n, each with a component, a mechanism, a statement, a confidence and the ledger IDs it cites, plus explicit unknowns.
- Code validates the diagnosis and refuses it when:
  - a ranked claim cites no evidence;
  - it cites an ID no call produced;
  - it cites a call that was refused or failed;
  - a cited call, run again, no longer gives the identical answer;
  - ranks are out of order, or confidence rises down the ranking.
- A **fake provider** replays a file of turns, so CI runs the whole investigation for free. Each file states its provenance. The file for `payments-latency` is **scripted by hand** to exercise the loop and the validator. It is not a model's output and measures nothing about model quality.
- A **spend cap** wraps any provider that charges. Before each turn, it checks that the spend so far plus the largest turn seen stays under `GUARDIA_MAX_SPEND_USD`, and aborts otherwise.

## Consequences

- An invented, failed or stale citation cannot pass, and tests prove each case, including evidence whose answer changes after the model saw it.
- Validation runs every cited call twice, which costs a little time and nothing else in replay.
- The brief asks for responses *recorded* from a real model. That needs an Anthropic provider, which is not built, and an owner's decision to spend on a key, under the cap. Until then, Phase 4's acceptance is only partly met, and PROGRESS.md says so.
