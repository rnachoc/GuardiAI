# GuardiAI

[![CI](https://github.com/rnachoc/GuardiAI/actions/workflows/ci.yml/badge.svg)](https://github.com/rnachoc/GuardiAI/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

An AI on-call engineer for a microservice system. When an alert fires, a small crew of agents investigates with read-only tools over metrics, logs, traces and change history, ranks root-cause hypotheses with cited evidence, and proposes a fix from a catalog of approved actions. A policy engine written in code decides whether each proposal may run, runs it with an audit trail, checks that the system recovered, and reverts if it did not. Afterwards an agent writes a blameless postmortem.

**The model proposes, the code decides.** No agent can run a command or change a system. It can only propose, and code decides ([ADR 2](docs/adr/0002-the-model-proposes-the-code-decides.md)).

GuardiAI is judged on three things: it finds the right cause, it never does anything unsafe, and it proves both with measured numbers. No number has been measured yet, so none is published.

> **Status: Phases 0 to 6 done; phases 7 to 10 remain ([plan](docs/HANDOFF.md)).** The marketplace GuardiAI watches runs with full telemetry, and 10 incident scenarios are recorded with a sealed ground truth. Five read-only tool servers answer from the live stack or a recording. The investigator validates every citation. The policy service decides, audits, and, after approval, restarts a failed service for real and verifies its recovery. No evaluation number has been measured yet, and no model is called (ADR 16). The [status table](#status) says exactly what exists, and [PROGRESS.md](PROGRESS.md) says what is next.

> **Synthetic only.** Mercadillo, the system GuardiAI watches, is a fictional marketplace. There are no real credentials anywhere in this repository.

## Quick start

You need Docker with Compose, and make. Nothing else, and no API key ([ADR 3](docs/adr/0003-docker-and-make-are-the-only-prerequisites.md)).

```bash
make up && make test
```

`make up` creates `.env` from [`.env.example`](.env.example), builds Mercadillo and waits until every service is healthy. The first run downloads images and dependencies and takes a few minutes.

| Open | What you see |
| --- | --- |
| http://127.0.0.1:3000 | Grafana, on the Mercadillo overview dashboard |
| http://127.0.0.1:8080/api/products | The shop's public API ([routes](mercadillo/README.md#public-api)) |
| http://127.0.0.1:8090 | GuardiAI's approval queue and audit timeline; `make e2e-remediation` fills them |
| http://127.0.0.1:8091 | Escalations GuardiAI sent to a person, through a stand-in for a Slack webhook |

`make verify` proves the stack works end to end, `make smoke` checks the backing services, and `make` lists every target. Before committing, `make hooks` installs the hook that stops a commit carrying a secret.

## Status

| Phase | Scope | Status | What exists today |
| --- | --- | --- | --- |
| 0 | Foundations | Done | Checks and CI, ADRs 1 to 3, and a stack with PostgreSQL, Redis and Kafka that `make smoke` proves works. |
| 1 | Mercadillo and observability | Done | Seven services, steady load, and traces, metrics and logs in Grafana. `make verify` proves it on every CI run. |
| 2 | Chaos and snapshots | Done | 10 scenarios with an injector, each recorded as a hashed bundle whose ground truth is re-checked on every build. 30 scenarios to go. |
| 3 | Read-only tool servers | Done | All five servers, live and replay, with allow-listed query subsets, caps, redaction and abuse tests. |
| 4 | Investigator | Done | Bounded loop, evidence ledger and validator, run on hand-scripted turns. No model is called, by the owner's decision ([ADR 16](docs/adr/0016-no-paid-model-runs.md)). |
| 5 | Policy engine | Done | Catalog, blast radius, decisions and a hash-chained audit log, all test-first. Nothing executes yet (Phase 6). |
| 6 | Remediation and verification | Done | Investigation to proposal to approval to execution, verified against Prometheus on the live stack in CI. Only `restart_service` acts for real. |
| 7 | Evaluation | In progress | Harness, deterministic graders, safety gates in CI and a generated [report](evals/REPORT.md). 10 of 40 scenarios; no model measured (ADR 16). |
| 8 | Console, postmortem and replay | In progress | A static [replay console](console) over the ten recordings, a live approval queue at 127.0.0.1:8090, and a model-free postmortem writer (`make postmortem`). |
| 9 | Security pack, model matrix, cost report | In progress | The [risks table](#risks) is complete and every row is tested. The policy service's own metrics are in Prometheus and Grafana. No model matrix or agent telemetry yet. |
| 10 | Release | Not started | Nothing. |

## Risks

What could go wrong, and what stops it. Every row links to the tests that enforce it, and `make test` runs them all. No row has been measured against a real model (ADR 16): the agents' side is tested with scripted turns.

| Risk | What stops it | Tests | Enforced |
| --- | --- | --- | --- |
| Prompt injection through logs, commits, trace attributes or alert annotations | Every tool server, and the alert's annotations, have free text redacted, wrapped as untrusted and flagged for injection patterns. A hypothesis whose only evidence is flagged text is rejected. Actions may only target a service a validated hypothesis names. The postmortem never repeats tool output | [logs and traces](mcp/test/logs-traces.test.ts), [changes](mcp/test/changes-runbooks.test.ts), [across components](agents/test/security.test.ts), [pipeline](agents/test/remediate.test.ts), [postmortem](agents/test/postmortem.test.ts) | Yes, with scripted turns |
| Expensive or abusive queries | PromQL and LogQL subsets parsed before anything runs, with caps on time range, points, series and lines | [metrics](mcp/test/metrics.test.ts), [logs](mcp/test/logs-traces.test.ts) | Yes |
| Secrets leak through logs | Redaction before any model sees text; an eval gate that fails on a leaked secret; gitleaks on every commit and in CI | [redaction](mcp/test/logs-traces.test.ts), [eval gate](evals/thresholds.yaml), [`.githooks/pre-commit`](.githooks/pre-commit) | Yes |
| An agent chains actions outside the catalog | The planner drops anything outside the catalog; the engine rejects it again; agents can only propose, never execute | [planner](agents/test/planner.test.ts), [`PolicyEngineTest`](policy/src/test/java/io/github/rnachoc/guardia/policy/PolicyEngineTest.java), [mcp-actions](mcp/test/actions.test.ts) | Yes |
| A confused deputy: an agent names another service to act on | A proposal may only target a component a validated hypothesis names, checked before it reaches mcp-actions; the engine checks allowed targets and blast radius | [planner](agents/test/planner.test.ts), [through mcp-actions](agents/test/remediate.test.ts), [`PolicyEngineTest`](policy/src/test/java/io/github/rnachoc/guardia/policy/PolicyEngineTest.java) | Yes |
| Runaway loops | Step, token and tool-call limits per investigation; a per-minute cap on proposals; an hourly limit per action; an idempotency key so nothing runs twice | [investigator](agents/test/investigator.test.ts), [mcp-actions](mcp/test/actions.test.ts), [`PolicyEngineTest`](policy/src/test/java/io/github/rnachoc/guardia/policy/PolicyEngineTest.java), [`ExecutorTest`](policy/src/test/java/io/github/rnachoc/guardia/policy/ExecutorTest.java) | Yes |
| Budget exhaustion | A token budget per investigation, and a spend cap that aborts before a turn could cross it | [investigator](agents/test/investigator.test.ts), [Anthropic provider](agents/test/anthropic.test.ts) | Yes, against a stand-in for the API |
| Someone tampers with the audit log | Each entry carries its time and the previous entry's SHA-256; any edit, deletion, reordering, redating or forged entry breaks the chain | [`AuditLogTest`](policy/src/test/java/io/github/rnachoc/guardia/policy/AuditLogTest.java), [`e2e_remediation.sh`](scripts/e2e_remediation.sh) | Yes, and end to end in CI |
| A diagnosis claims something it cannot show | Every claim cites ledger IDs; invented, failed or changed evidence is rejected | [investigator](agents/test/investigator.test.ts) | Yes |
| An agent changes a system | Only the policy service executes, after its checks and approval ([ADR 18](docs/adr/0018-execution-through-one-service-with-the-docker-socket.md)) | [`ExecutionControllerTest`](policy/src/test/java/io/github/rnachoc/guardia/policy/ExecutionControllerTest.java), [`e2e_remediation.sh`](scripts/e2e_remediation.sh) | Yes, and end to end in CI |
| A real secret is committed | gitleaks on staged changes and on the whole history | [`.githooks/pre-commit`](.githooks/pre-commit), [CI](.github/workflows/ci.yml) | Yes |
| A credential is placed in the example configuration | Only empty or `local-only` values are allowed for secret-like keys | [`tests/test_foundations.py`](tests/test_foundations.py) | Yes |
| A shopper reaches an internal endpoint through the gateway | Each public route names one endpoint, and path variables are encoded | [`GatewayControllerTest`](mercadillo/gateway/src/test/java/io/github/rnachoc/mercadillo/gateway/GatewayControllerTest.java) | Yes |

## Repository layout

| Folder | What it holds |
| --- | --- |
| [`mercadillo/`](mercadillo) | The fictional marketplace GuardiAI watches |
| [`chaos/`](chaos) | Fault injector, incident scenarios and the snapshot recorder |
| [`mcp/`](mcp) | Read-only tool servers, and the one that only creates proposals |
| [`agents/`](agents) | Triage, investigator, planner, critic and postmortem writer |
| [`policy/`](policy) | The policy and execution service, with the audit log |
| [`console/`](console) | The web console |
| [`evals/`](evals) | The evaluation harness and its generated report |
| [`docs/`](docs) | Decision records and documentation |
| [`deploy/`](deploy) | Helm and kind, optional |

## Licence

[MIT](LICENSE), © 2026 Ignacio Rodríguez Caro.
