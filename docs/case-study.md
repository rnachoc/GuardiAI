# Case study: GuardiAI

*Ignacio Rodriguez Caro · November 2026*

## The problem

When an alert fires at 3 a.m., the slow part is not the fix. It is finding out what broke, and being sure the fix is safe. Rule-based automation ("alert X, so restart service Y") is fast but blind. It acts on the symptom, wherever the cause is. An AI agent could investigate like an engineer does. But an agent that can change production is a new risk: it can be wrong, it can be manipulated by text it reads, and it can act too widely.

## What I built

GuardiAI is an on-call agent for a seven-service marketplace, Mercadillo, which I built to be broken on purpose. It runs on Spring Boot, Postgres, Redis and Kafka, and is fully traced with OpenTelemetry.

- **Read-only investigation.** Agents query metrics, logs, traces and change history through six MCP servers. Every tool call goes into an evidence ledger with a hash. A diagnosis must cite ledger IDs, and code re-runs every cited call. A claim that cites nothing, cites a failed call, or cites a call that now gives a different answer is rejected.
- **Untrusted text is data.** Logs, commit messages, span attributes and alert annotations are redacted, wrapped and flagged. A hypothesis whose only evidence is flagged text is rejected.
- **Agents propose; code decides.** A policy engine written in Java checks every proposal against a catalog of nine actions, the allowed targets, the blast radius on the dependency graph, preconditions, hourly limits and a kill switch. It needs human approval in suggest mode.
- **Execute, verify, revert.** One service holds the Docker socket. It claims an idempotency key, runs the action, waits for metrics to settle, checks recovery against Prometheus, reverts if recovery is not confirmed, and calls a person. Every step goes into a hash-chained, timestamped audit log.

## Results, with what they do and do not show

Twenty incidents are recorded as replayable bundles: thirteen incidents, four decoys and three prompt injections. Each one ran three times against two systems:

| | Rule-based automation | GuardiAI (scripted turns) |
| --- | --- | --- |
| Proposes only acceptable actions | 6 of 60 runs | 9 of 9 runs |
| Forbidden actions proposed, over all runs | 54 (in 18 of 20 incidents) | 0 |
| Successful injections | 9 (all three scenarios, every run) | 0 |
| Decoys left alone | 0 of 12 runs | 3 of 3 runs |

**The honest caveat.** No paid model was called (ADR 16). GuardiAI ran on three scenarios with hand-scripted model turns. These numbers prove that the guardrails behave as designed: the validator, the planner gate and the policy engine. They say nothing yet about how well a model diagnoses. The harness is built so that an API key fills in the rest.

End to end, CI does this on every push:

1. It kills a real service.
2. The policy service refuses to execute the fix until a person approves it.
3. After approval, it restarts the service.
4. It waits for the metrics to settle and confirms recovery in Prometheus.
5. It checks that the audit chain is intact.

## One failure, explained

The first version of verification "confirmed" every recovery instantly, including ones that had not happened. The check was a 5xx rate over a one-minute window. Right after the restart, that window still held the samples from before the failure, so it read healthy. The executor was grading itself on stale data.

The fix had three parts:

- A settle period of 70 seconds before the first check, longer than the rate window.
- A pause between polls.
- A test that fails if verification ever passes before the window has turned over.

The lesson generalises: **a check that cannot fail is not a check.** Every guardrail in GuardiAI now has a test that shows it saying no.

## What I would do next

- Record real model runs for all scenarios and fill in the model matrix and cost per incident.
- Grow the suite from 20 to 40 scenarios.
- Implement the other eight catalog actions with their reverts.
- Instrument the agents with OpenTelemetry's GenAI conventions.
