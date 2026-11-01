# Agents

GuardiAI's agents in TypeScript ([ADR 13](../docs/adr/0013-the-investigator-and-its-evidence-ledger.md)).

**Status: in progress (Phase 4).**

What exists:

- The **investigator** loop, bounded by steps, a token budget and a cap on tool calls per step. It reaches the five read-only tool servers over MCP.
- The **evidence ledger**: every tool call gets an ID and the hash of its answer.
- The **validator**: a diagnosis is refused when a claim has no evidence, cites an unknown or failed call, or cites a call whose answer changed when run again.
- A **fake provider** that replays turns from `responses/`, and a **spend cap** for any provider that charges.

What does not exist yet:

- A provider for the Anthropic API, and responses recorded from a real model. The file in `responses/` is scripted by hand and says so in its `provenance`.
- Triage, the remediation planner, the critic and the postmortem writer.

`make test-agents` runs the investigator end to end on the `payments-latency` recording, with no API key.
