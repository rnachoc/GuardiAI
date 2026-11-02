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
- `scale_deployment` adds one replica: a copy of the container's image, environment, healthcheck and memory limit, labelled `io.guardia.replica-of` instead of with Compose's labels, on the same networks under the service's name. `scale_back` removes it. A second replica is refused while one runs. Its verification counts the instances that reported to Prometheus in the last 30 seconds, since `up` does not exist for services that push over OTLP.
- `RoutingRunner` sends each action and each revert to the runner that implements it. An action no runner implements still fails its dry run.
- `rollback_deploy`, `toggle_flag` and `increase_pool_size` stay unimplemented until the stack has a deploy history, a flags service and live pool settings.
- `make e2e-actions` runs the four new actions on the live stack and runs in CI. It also checks that the standby really serves charges.

## Consequences

- Six of nine actions act for real, each verified against Prometheus after the settle period.
- The stack runs one more JVM, the idle standby.
- A replica runs and reports, but measured live it served no requests: checkout reaches inventory through Toxiproxy, which keeps dialling the first address it resolves. Scaling out adds capacity only once a load balancer sits in front of the replicas.
- Clearing the cache is started detached, so the runner does not see the exec's exit code. The command was checked by hand (40 keys before, 0 after), and verification checks the catalog's latency afterwards.
- `standby_healthy` and `database_healthy` are read from Docker's health status of `payments-standby` and `postgres` on every judgement, and override `deploy/policy/facts.json`. Checked live: with the standby stopped, a failover is rejected because `standby_healthy` does not hold. The other preconditions still come from the file, kept by hand.
- Catalog limits allow one failover an hour, so `make e2e-actions` runs at most once an hour on the same stack.
