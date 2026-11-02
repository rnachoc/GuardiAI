# 21. Actions through the tools that already hold the wiring

- **Status:** Accepted
- **Date:** 2026-11-02
- **Author:** Ignacio Rodriguez Caro

## Context

Only `restart_service` acted for real. The catalog lists nine actions, and a runner that pretends is worse than none: the executor would record success for something that never happened ([ADR 18](0018-execution-through-one-service-with-the-docker-socket.md)). Each real action needs a lever in the stack. Some levers already exist: Docker can pause a container and run a command in one, and every connection between checkout and payments already runs through Toxiproxy ([ADR 8](0008-faults-through-toxiproxy-and-the-docker-api.md)).

## Decision

- `pause_consumer` and `resume_consumer` pause and unpause the notifier's container through the Docker API. The consumer stops with the process, and its offsets stay in Kafka, so resuming catches up.
- `clear_cache` on catalog deletes the `products::*` keys of its Spring cache, with `redis-cli` run as an exec in the Redis container.
- `failover` of payments points checkout's Toxiproxy proxy at a second payments container, `payments-standby`, and `fail_back` points it at the primary again. Nothing restarts and no client is reconfigured.
- `RoutingRunner` sends each action and each revert to the runner that implements it. An action no runner implements still fails its dry run.
- `scale_deployment`, `rollback_deploy`, `toggle_flag` and `increase_pool_size` stay unimplemented until the stack has replicas, a deploy history, a flags service and live pool settings.
- `make e2e-actions` runs the four new actions on the live stack and runs in CI. It also checks that the standby really serves charges.

## Consequences

- Five of nine actions act for real, each verified against Prometheus after the settle period.
- The stack runs one more JVM, the idle standby.
- Clearing the cache is started detached, so the runner does not see the exec's exit code. The command was checked by hand (40 keys before, 0 after), and verification checks the catalog's latency afterwards.
- The failover's preconditions come from `deploy/policy/facts.json`, kept by hand. `standby_healthy` is true because the standby exists, not because something measured it. A facts collector is still to do.
- Catalog limits allow one failover an hour, so `make e2e-actions` runs at most once an hour on the same stack.
