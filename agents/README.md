# Agents

GuardiAI's agents in TypeScript ([ADR 13](../docs/adr/0013-the-investigator-and-its-evidence-ledger.md)).

**Status: in progress (Phase 4).**

What exists:

- The **investigator** loop, bounded by steps, a token budget and a cap on tool calls per step. It reaches the five read-only tool servers over MCP.
- The **evidence ledger**: every tool call gets an ID and the hash of its answer.
- The **validator**: a diagnosis is refused when a claim has no evidence, cites an unknown or failed call, or cites a call whose answer changed when run again.
- A **fake provider** that replays turns from `responses/`, and a **spend cap** for any provider that charges.
- An **Anthropic provider** on the official SDK, using `claude-opus-5-5`, that records its turns for replay ([ADR 14](../docs/adr/0014-claude-through-the-official-sdk-recorded-for-replay.md)). It is tested against a local stand-in for the API, and has never called the real one.
- **Telemetry** (`src/telemetry.ts`): each investigation becomes an OTLP trace and metrics with OpenTelemetry's GenAI conventions, sent to `OTEL_EXPORTER_OTLP_ENDPOINT` when set; `make investigate` sets it whenever the stack runs.
- The **postmortem writer** (`src/postmortem.ts`), plain code, not a model. It renders the alert, the ranked hypotheses with the evidence they cite, the unknowns, the actions with their verification and rollback, the drafts the planner gate refused and why, and the evidence ledger. Tool output is never repeated, only the call and its hash, so untrusted log text cannot travel into the document. `make postmortem SCENARIO=injection-log-restart-inventory` prints one. Given a saved `GET /audit` answer (`--audit <file>`), it adds the policy service's timeline for the run's proposals.

What does not exist yet:

- Responses recorded from a real model. The file in `responses/` is scripted by hand and says so in its `provenance`. To record one:

  ```bash
  GUARDIA_MODEL_PROVIDER=anthropic GUARDIA_MAX_SPEND_USD=1.00 ANTHROPIC_API_KEY=... make investigate SCENARIO=payments-latency
  ```
- Triage and the critic. The postmortem does not yet include the policy service's audit timeline or suggest runbook edits.

`make test-agents` runs the investigator end to end on the `payments-latency` recording, with no API key.
