# Progress

The single source of truth for what is done and what is next. A phase is marked done only when its acceptance criteria are met and tested, and CI is green. A test checks that this table and the README's status table agree.

## Phases

| Phase | Name | Status | Release |
| --- | --- | --- | --- |
| 0 | Foundations | Done | v0.0.0 |
| 1 | Mercadillo and observability | Not started | — |
| 2 | Chaos and snapshots | Not started | — |
| 3 | Read-only tool servers | Not started | — |
| 4 | Investigator | Not started | — |
| 5 | Policy engine | Not started | — |
| 6 | Remediation and verification | Not started | — |
| 7 | Evaluation | Not started | — |
| 8 | Console, postmortem and replay | Not started | — |
| 9 | Security pack, model matrix, cost report | Not started | — |
| 10 | Release | Not started | — |

Phases 0 to 7 are the minimum publishable version.

## Phase 0: Foundations

**Acceptance:** `make up` starts the skeleton and CI is green.

Done, checked locally and in CI:

- Repository, MIT licence, `.editorconfig`, `.gitattributes`, `.gitignore` and an `.env.example` with local-only placeholders.
- `Makefile` whose targets run every check in a pinned container, so Docker and make are the only prerequisites ([ADR 3](docs/adr/0003-docker-and-make-are-the-only-prerequisites.md)).
- Compose skeleton with Mercadillo's backing services: PostgreSQL 18.6, Redis 8.10.3 and Kafka 4.3.2 in KRaft mode, pinned by digest, with healthchecks. `make up` waits until all three are healthy, and `make smoke` proves each one works: a query, a value stored and read back, a message produced and consumed.
- gitleaks 8.30.1 as a pre-commit hook (`make hooks`) and over the whole history in CI.
- CI workflow with four jobs: lint, tests, secret scan and stack.
- Foundation tests that enforce the rules above: placeholders only, everything pinned, decision records complete, this table in step with the README.
- ADRs 1 to 3.

Verified: the [first CI run](https://github.com/rnachoc/GuardiAI/actions/runs/41301400487) passed all four jobs. The secret scan read all 7 commits, the 13 foundation tests ran, and the stack started and passed the smoke test on a GitHub runner.

## Next

Phase 1: the Mercadillo services, a load generator, and the OpenTelemetry Collector feeding Prometheus, Loki, Tempo and Grafana. Done when a dashboard shows live traffic and a trace crosses at least four services.
