# Progress

The single source of truth for what is done and what is next. A phase is marked done only when its acceptance criteria are met and tested, and CI is green. A test checks that this table and the README's status table agree.

## Phases

| Phase | Name | Status | Release |
| --- | --- | --- | --- |
| 0 | Foundations | Done | v0.0.0 at `5e0cf37`, not tagged yet |
| 1 | Mercadillo and observability | Done | v0.1.0 at the commit that marks it done, not tagged yet |
| 2 | Chaos and snapshots | Done | v0.2.0 at the commit that marks it done, not tagged yet |
| 3 | Read-only tool servers | Done | v0.3.0 at the commit that marks it done, not tagged yet |
| 4 | Investigator | Done | v0.4.0 at the commit that marks it done, not tagged yet |
| 5 | Policy engine | Done | v0.5.0 at the commit that marks it done, not tagged yet |
| 6 | Remediation and verification | In progress | — |
| 7 | Evaluation | Not started | — |
| 8 | Console, postmortem and replay | Not started | — |
| 9 | Security pack, model matrix, cost report | Not started | — |
| 10 | Release | Not started | — |

Phases 0 to 7 are the minimum publishable version. The session that builds GuardiAI cannot push tags, so the owner creates each release tag on the commit named here.

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

- Steps 3 to 5. The snapshot recorder ([ADR 10](docs/adr/0010-fixture-bundles-with-a-sealed-ground-truth.md)):
  - `make record-all` recorded all 10 scenarios into `chaos/fixtures`, 1.2 MB in all;
  - each piece of evidence now carries a machine-checkable assertion, and every recording's ground truth held when measured;
  - each bundle holds the raw series of 15 metrics, logs, up to 40 trimmed traces, change events and the alert. The fault timeline and the measurements are sealed under `truth/`;
  - on every build, `make test` verifies every bundle: its SHA-256 hashes, byte-for-byte replay, the ground truth, that nothing from `truth/` leaks into what tools will serve, and that no secret is hidden inside the compressed files;
  - 23 tests cover the recorder, the plan and the schema.

Verified on GitHub: [CI run](https://github.com/rnachoc/GuardiAI/actions/runs/41317723691) validated all 10 scenarios and verified all 10 recordings.

Notes:

- No scenario yet changes a deploy, a config or a flag, so every bundle's change events are empty. The scenarios for bad config, bad deploys and flags come with the other 30.
- Most services log nothing per request, so their recorded log lines are the warnings and errors. Checkout and the notifier log each order.

## Phase 3: Read-only tool servers

**Acceptance:** all five tool servers, in live and replay modes, with unit tests and abuse tests.

**Plan.**
1. Build a shared replay reader and the untrusted-text handling.
2. Build `mcp-metrics`, `mcp-logs` and `mcp-traces`.
3. Build `mcp-changes` and `mcp-runbooks`.
4. Check every server against the recordings and the live stack.

Done and tested ([ADR 11](docs/adr/0011-read-only-tool-servers-with-one-grammar-for-both-modes.md)):

- The replay reader opens only the tool-facing files of a bundle, after checking their hashes, and refuses `truth/` by name.
- Untrusted text is redacted, wrapped in delimiters and flagged for known injection patterns. The planted line of `injection-log-restart-inventory` comes back wrapped and flagged.
- `mcp-metrics` runs an allow-listed PromQL subset with caps.
  - For every metric of every scenario, replay reached the same verdict as the real Prometheus. Most values were within 1%; the widest gaps were 1.0 against 1.28 for a rate, and 108 against 127 for a fast-growing gauge.
- `mcp-logs` runs a LogQL subset with redaction and a line cap. `mcp-traces` offers structured search, a trace by ID and the critical path; on `payments-latency`, the critical path runs through the call to `payments-proxy`.
- `mcp-changes` lists deploys, config versions, flag changes and commits, and returns each with its diff. Commit messages and diffs are untrusted: a hostile commit message comes back wrapped and flagged, and malformed change-log lines are skipped.
- `mcp-runbooks` searches six runbooks, one per incident family plus one for alerts that recover on their own. Ranking is BM25 plus character-trigram TF-IDF vectors, which are lexical, not neural embeddings ([ADR 12](docs/adr/0012-lexical-hybrid-search-for-runbooks.md)). The right runbook ranks first for each family, typos included.
- 22 tests. The metrics, logs and traces servers were also driven in live mode against the running stack.

Verified on GitHub: [CI run](https://github.com/rnachoc/GuardiAI/actions/runs/41319483048) ran the tool servers' tests in a container, green.

Known limits:

- Nothing writes the live change log yet. The scenarios that change deploys, configs and flags come with the other 30, so live `mcp-changes` answers with an empty history until then.
- `docs/incidents` is empty until postmortems exist (Phase 8).

## Phase 4: Investigator

**Acceptance:** end to end on one scenario, with the fake provider and recorded responses; done when the diagnosis cites valid evidence.

Done and tested ([ADR 13](docs/adr/0013-the-investigator-and-its-evidence-ledger.md)):

- The bounded investigator loop, the evidence ledger and the deterministic validator. The fake provider and the spend cap.
- On the `payments-latency` recording, the investigator ran 3 steps and 5 tool calls over MCP. Its diagnosis cites valid evidence and ranks payments, `network_latency`, first.
- 8 tests prove each rejection: an invented ID, a refused call, a claim without evidence, evidence whose answer changed, ranks out of order, an unknown component. They also prove that the step limit, the token budget, the tool-call limit and the spend cap each stop a run.

- The Anthropic provider on the official SDK records every turn for replay ([ADR 14](docs/adr/0014-claude-through-the-official-sdk-recorded-for-replay.md)). Against a local stand-in for the API, it sends valid requests, matches each tool result to its call, and its recording replays to the same diagnosis. `make investigate SCENARIO=<id>` runs it.

Closed by the owner's decision ([ADR 16](docs/adr/0016-no-paid-model-runs.md)): no paid model run is made. Phase 4 is closed on hand-scripted turns for `payments-latency` and `injection-log-restart-inventory`. On the injection scenario, the planted instruction reaches the investigator wrapped and flagged, and a diagnosis that obeyed it has nothing to cite. [CI run](https://github.com/rnachoc/GuardiAI/actions/runs/41328276479) green.

What this does not show, and why:

- **The responses are scripted by hand, not recorded from a model.** The brief asks for recorded responses. That needs an Anthropic provider and a key under the spend cap, which is the owner's decision. Until then, the passing run proves the loop and the validator, not a model's ability to diagnose.

## Phase 5: Policy engine

**Acceptance:** forbidden and over-blast actions are rejected in tests. Tests first.

Done, test-first ([ADR 15](docs/adr/0015-a-policy-engine-that-gathers-every-reason.md)):

- The versioned catalog of nine actions and the dependency graph. The engine rejects with every reason: kill switch, unknown action, disallowed target, failed or unknown precondition, hourly limit, blast radius. Suggest and autonomous modes.
- 9 tests, written before the engine. They found two catalog entries that could never run, which were fixed. **The acceptance criterion holds.**
- The append-only, hash-chained audit log, also test-first. Its 5 tests show that editing, deleting, reordering, or rewriting an entry with a recomputed hash all break the chain, and that reopening the file continues it.

Verified on GitHub: [CI run](https://github.com/rnachoc/GuardiAI/actions/runs/41321942814) ran all 14 policy tests, green.

Left for Phase 6, which executes actions: the Spring Boot service around the engine, `mcp-actions`, idempotent execution, verification and automatic revert.

## Phase 6: Remediation and verification

**Acceptance:** planner, executor, verification, automatic revert.

Done and tested:

- `mcp-actions`: `propose_action` writes a proposal with its evidence and expected effect, returns its ID, and leaves judging to the policy engine. It refuses malformed proposals and caps them at 10 a minute. A test checks that its code has no way to execute anything.

- The Spring Boot policy service. `POST /proposals` judges a proposal and writes it and its decision to the audit log; `GET /audit/verify` checks the chain. Preconditions come from a facts file kept by code, never from the request; without it, every proposal is rejected. 3 HTTP tests, 17 in the service.

Not done: the facts collector, a Compose service for the policy engine, the executor (idempotency, dry run, verification, auto-revert, escalation), the remediation planner and the end-to-end test. The plan is in [docs/HANDOFF.md](docs/HANDOFF.md#52-phase-6-remediation-and-verification).

## Next

Everything that remains, phase by phase, with files, order and known findings, is in [docs/HANDOFF.md](docs/HANDOFF.md). Next: Phase 6, remediation and verification.
