# Progress

The single source of truth for what is done and what is next. A phase is marked done only when its acceptance criteria are met and tested, and CI is green. A test checks that this table and the README's status table agree.

## Phases

| Phase | Name | Status | Release |
| --- | --- | --- | --- |
| 0 | Foundations | Done | v0.0.0 |
| 1 | Mercadillo and observability | In progress | — |
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

## Phase 1: Mercadillo and observability

**Acceptance:** a dashboard shows live traffic, and a trace crosses at least four services.

**Plan.** Seven Spring Boot services in one Maven build and one Dockerfile ([ADR 4](docs/adr/0004-mercadillo-as-one-spring-boot-build.md)); the OpenTelemetry agent in every service, one Collector, and Prometheus, Loki, Tempo and Grafana ([ADR 5](docs/adr/0005-opentelemetry-agent-and-one-collector.md)); k6 for steady shoppers ([ADR 6](docs/adr/0006-k6-for-steady-shoppers.md)); a check that proves the criteria on the running stack.

Done and checked locally:

- `gateway`, `catalog`, `cart`, `checkout`, `payments`, `inventory` and `notifier`, with 30 unit tests. `make test-mercadillo` compiles them with every warning treated as an error, and runs the tests in a container.
- The full stack starts with `make up`. Every Mercadillo service reports healthy, and k6 sends 4 shoppers per second.
- `make verify` proves the criteria on the running stack. Locally, it measured:
  - a purchase through the gateway, with the cart cleared afterwards;
  - live traffic in Prometheus;
  - a checkout trace that crosses all 7 services, including the hop over Kafka;
  - logs from all 7 services in Loki, carrying trace IDs;
  - all 14 Prometheus queries of the overview dashboard returning data through Grafana.
- Every status code in the [public API table](mercadillo/README.md#public-api) was checked against the running stack, including out of stock.
- `make verify` fails when the gateway is stopped, so it does not pass on a broken stack.

Still to do before Phase 1 is done: see CI run `make verify` green on GitHub, then tag `v0.1.0`.

## Next

Phase 2: the `guardia-chaos` injector, the first 10 scenarios, and the snapshot recorder. Done when each scenario can be recorded and replayed reproducibly.
