# Progress

The single source of truth for what is done and what is next. A phase is marked done only when its acceptance criteria are met and tested, and CI is green. A test checks that this table and the README's status table agree.

## Phases

| Phase | Name | Status | Release |
| --- | --- | --- | --- |
| 0 | Foundations | Done | v0.0.0 at `5e0cf37`, not tagged yet |
| 1 | Mercadillo and observability | Done | v0.1.0 at the commit that marks it done, not tagged yet |
| 2 | Chaos and snapshots | In progress | — |
| 3 | Read-only tool servers | Not started | — |
| 4 | Investigator | Not started | — |
| 5 | Policy engine | Not started | — |
| 6 | Remediation and verification | Not started | — |
| 7 | Evaluation | Not started | — |
| 8 | Console, postmortem and replay | Not started | — |
| 9 | Security pack, model matrix, cost report | Not started | — |
| 10 | Release | Not started | — |

Phases 0 to 7 are the minimum publishable version. The session that builds Guardia cannot push tags, so the owner creates each release tag on the commit named here.

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

Done, checked locally and in CI:

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

Verified on GitHub: [CI run](https://github.com/rnachoc/GuardiAI/actions/runs/41305120651) passed all four jobs. On the runner, `make verify` measured a checkout trace across all 7 services, logs from all 7, and all 14 dashboard queries returning data.

## Phase 2: Chaos and snapshots

**Acceptance:** each scenario can be recorded and replayed reproducibly.

**Plan.**
1. Define the scenario format and write the first 10 scenarios.
2. Build the injector: Toxiproxy on four connections, plus container kill, container pause and planted logs.
3. Build the snapshot recorder, which stores metrics, logs, traces and change events as a fixture bundle with a hash manifest.
4. Record each scenario and confirm that its evidence queries return what the scenario expects.
5. Show that replaying a bundle is byte-for-byte reproducible.

Done and tested:

- Step 1. The strict scenario schema, 10 scenarios (6 incidents, 2 decoys, 2 injections), `guardia-chaos validate`, and tests. `make test` runs them in a container ([ADR 7](docs/adr/0007-scenarios-as-validated-yaml-in-typescript.md)).
- Step 2. The injector ([ADR 8](docs/adr/0008-faults-through-toxiproxy-and-the-docker-api.md)):
  - Toxiproxy sits on four connections;
  - latency, timeout, kill, pause and planted-log faults;
  - `make chaos SCENARIO=<id>` runs a scenario, reverts every fault on time or on interruption, and writes a run record;
  - tests cover the run plan and the toxics.
- All 10 scenarios were run against the live stack. Each piece of evidence was measured once mid-fault and once at the end, and it held:

  | Scenario | What was measured |
  | --- | --- |
  | `payments-latency` | checkout's p95 to `payments-proxy` was 2.07 s; payments' own p95 was 0.072 s |
  | `payments-timeouts` | checkout answered 502 at 0.45/s with no 500, and logged the call to `payments-proxy` |
  | `inventory-killed` | checkout answered 5xx at 0.88/s; inventory came back healthy when reverted |
  | `catalog-db-latency` | catalog's p95 for `/products` was 0.97 s, and Tempo held catalog database spans over 300 ms |
  | `cart-redis-latency` | cart's p95 was 0.72 s, and Tempo held Redis spans over 250 ms |
  | `notifier-paused` | orders kept arriving at 1.38/s, while broker-side lag grew from 133 to 354 |
  | `decoy-catalog-blip` | catalog's p95 was 2.25 s mid-fault and 0.005 s at the end, recovered on its own |
  | `decoy-notifier-hiccup` | broker-side lag peaked at 31 and drained to 0 on its own |
  | `injection-log-restart-inventory` | checkout's p95 to payments was 2.43 s, inventory had no 5xx, and the planted line was in Loki |
  | `injection-annotation-rollback` | inventory's request rate fell to zero |

What those runs found, and what changed:

- **Consumer lag.** The lag the notifier reports about itself stays at 0 while it is frozen, because a frozen process reports nothing. A broker-side exporter (kafka-exporter 1.10.0) now measures lag, and two scenarios and the dashboard use it.
- **Dead process.** A killed process's metrics disappear instead of falling to zero, so the evidence queries now read "no series" as zero.
- **Checkout errors.** When a dependency did not answer, checkout answered 500 with a stack trace. It now answers a clean 502, counts the checkout as `dependency_failed`, and logs which call failed.
- **Trace attribute.** Two trace queries used `db.system.name`, but the agent emits the older `db.system`, so they matched nothing. They now use `db.system`, and match.

Not done:

- Step 3, the snapshot recorder. Steps 4 and 5 depend on it.
