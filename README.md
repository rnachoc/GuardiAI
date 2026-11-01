# Guardia

[![CI](https://github.com/rnachoc/GuardiAI/actions/workflows/ci.yml/badge.svg)](https://github.com/rnachoc/GuardiAI/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

An AI on-call engineer for a microservice system. When an alert fires, a small crew of agents investigates with read-only tools over metrics, logs, traces and change history, ranks root-cause hypotheses with cited evidence, and proposes a fix from a catalog of approved actions. A policy engine written in code decides whether each proposal may run, runs it with an audit trail, checks that the system recovered, and reverts if it did not. Afterwards an agent writes a blameless postmortem.

**The model proposes, the code decides.** No agent can run a command or change a system. It can only propose, and code decides ([ADR 2](docs/adr/0002-the-model-proposes-the-code-decides.md)).

Guardia is judged on three things: it finds the right cause, it never does anything unsafe, and it proves both with measured numbers. No number has been measured yet, so none is published.

> **Status: Phase 0, foundations.** The repository, the checks and a stack with Mercadillo's backing services exist. There are no agents, no services and no evaluations yet. The [status table](#status) says exactly what exists, and [PROGRESS.md](PROGRESS.md) says what is next.

> **Synthetic only.** Mercadillo, the system Guardia watches, is a fictional marketplace. There are no real credentials anywhere in this repository.

## Quick start

You need Docker with Compose, and make. Nothing else, and no API key ([ADR 3](docs/adr/0003-docker-and-make-are-the-only-prerequisites.md)).

```bash
make up && make test
```

`make up` creates `.env` from [`.env.example`](.env.example) and waits until every service is healthy. `make smoke` proves each running service works, and `make` lists every target. Before committing, `make hooks` installs the hook that stops a commit carrying a secret.

## Status

| Phase | Scope | Status | What exists today |
| --- | --- | --- | --- |
| 0 | Foundations | In progress | Checks, CI workflow, ADRs 1 to 3, and a stack with PostgreSQL, Redis and Kafka. CI has not run on GitHub yet. |
| 1 | Mercadillo and observability | Not started | Nothing. |
| 2 | Chaos and snapshots | Not started | Nothing. |
| 3 | Read-only tool servers | Not started | Nothing. |
| 4 | Investigator | Not started | Nothing. |
| 5 | Policy engine | Not started | Nothing. |
| 6 | Remediation and verification | Not started | Nothing. |
| 7 | Evaluation | Not started | Nothing. No number has been measured. |
| 8 | Console, postmortem and replay | Not started | Nothing. |
| 9 | Security pack, model matrix, cost report | Not started | Nothing. |
| 10 | Release | Not started | Nothing. |

## Risks

What could go wrong, and what will stop it. Until the security tests of Phase 9 exist, no line in this table is enforced, and each says so.

| Risk | What will stop it | Where | Enforced |
| --- | --- | --- | --- |
| A real secret is committed | gitleaks on staged changes and on the whole history | [`.githooks/pre-commit`](.githooks/pre-commit), [CI](.github/workflows/ci.yml) | Locally. Not yet on GitHub. |
| A credential is placed in the example configuration | A test that only allows empty or `local-only` values for secret-like keys | [`tests/test_foundations.py`](tests/test_foundations.py) | Yes |
| An agent changes a system | Agents only reach read-only tools; actions are proposals that the policy engine decides | [ADR 2](docs/adr/0002-the-model-proposes-the-code-decides.md) | Not built yet |
| Text in logs, commits, traces or alerts steers an agent | Untrusted text is wrapped and flagged; attack scenarios in the evaluation bank | [ADR 2](docs/adr/0002-the-model-proposes-the-code-decides.md) | Not built yet |

## Repository layout

| Folder | What it holds |
| --- | --- |
| [`mercadillo/`](mercadillo) | The fictional marketplace Guardia watches |
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
