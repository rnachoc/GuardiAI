# Policy and execution service

The code that decides every proposal ([ADR 2](../docs/adr/0002-the-model-proposes-the-code-decides.md), [ADR 15](../docs/adr/0015-a-policy-engine-that-gathers-every-reason.md)).

**Status: in progress (Phase 5).**

What exists, test-first:

- [`catalog-v1.yaml`](src/main/resources/catalog-v1.yaml): the versioned catalog of the nine allowed actions, each with its targets, risk tier, preconditions, hourly limit, maximum blast radius, verification query and auto-revert.
- [`dependency-graph.yaml`](src/main/resources/dependency-graph.yaml), from which each action's blast radius is computed.
- `PolicyEngine.decide`, which rejects with every reason when the kill switch is on, or when the action is outside the catalog, the target is not allowed, a precondition fails or is unknown, the hourly limit is reached, or the blast radius is too large. In suggest mode, every action needs approval; in autonomous mode, only low-risk ones run alone.

- `AuditLog`: an append-only JSON Lines file. Each entry is chained to the one before it by a SHA-256 hash, and `verify()` reports any edit, deletion, reordering or forged entry.

- The Spring Boot service: `POST /proposals` judges and audits; `GET /audit?limit=N` returns the latest entries, oldest first, each with the time it was written inside its hash; `GET /audit/verify` checks the chain; `GET /metrics` serves GuardiAI's own metrics in Prometheus's format, counted from the audit log, which Prometheus scrapes for the dashboard's GuardiAI row. Facts come from `guardia.facts.file`, kept by code; a missing file makes every precondition unknown, so everything is rejected.

- The **approval queue** at http://127.0.0.1:8090 (`GET /proposals` underneath). It lists every judged proposal with its verdict, the engine's reasons, the services it could reach, approvals and outcome, and lets a person approve or execute. Below it, a **timeline** of the audit log, newest first, says whether the hash chain is intact.
- **Escalation by webhook.** When a remediation fails to recover the system, the executor posts `{"text": …}` to `GUARDIA_ESCALATION_WEBHOOK_URL`, which any Slack-compatible incoming webhook accepts. Every escalation is also logged, and a webhook that is down or refuses never breaks the executor. In Compose it points at the local stand-in, which shows them at http://127.0.0.1:8091.

- **Real actions.** `restart_service`, `pause_consumer`, `resume_consumer` and `clear_cache` through the Docker API (`DockerRunner`), and `failover` of payments to `payments-standby` through Toxiproxy (`ToxiproxyRunner`), routed by `RoutingRunner`. `make e2e-actions` runs each on the live stack.

What does not exist yet: a facts collector (preconditions come from `deploy/policy/facts.json`, kept by hand), and runners for `scale_deployment`, `rollback_deploy`, `toggle_flag` and `increase_pool_size`, which fail their dry run.

`make test-policy` runs the tests in a container.
