# 8. Faults through Toxiproxy and the Docker API

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

Scenarios need faults that are realistic, precise and fully reversible: slowing one connection without touching the others, killing or freezing one process, and planting hostile text where an agent will read it. A fault that is not reverted would corrupt every later run, and a run interrupted halfway must not leave the stack broken.

## Decision

- **Toxiproxy 2.13.0** sits on four connections: checkout to payments, checkout to inventory, catalog to PostgreSQL, and cart to Redis. Each has its own network name (`payments-proxy`, `inventory-proxy`, `catalog-db-proxy`, `cart-redis-proxy`), so the callers' metrics show which path a request took.
- `guardia-chaos run` applies latency and timeout faults as Toxiproxy toxics, and kill and pause faults through the Docker Engine API over its socket, finding containers by their Compose labels. It plants log lines through Loki's push API, labelled as the service they impersonate.
- The run follows a plan computed from the scenario, which tests cover without a stack. Every fault is reverted at its time, at the end, or on interruption, and `make chaos-reset` removes any toxic left behind. Each run writes a record of what happened and when, for the snapshot recorder.
- The chaos container is the only part of Guardia given the Docker socket. Agents and tool servers never are.

## Consequences

- Faults are exact and repeatable, and a run of `payments-latency` showed the evidence its scenario claims. On the live stack, checkout's p95 to `payments-proxy` was 2.07 s while payments' own p95 was 0.072 s.
- Traffic on those four connections always crosses Toxiproxy, which adds a hop with negligible latency when no toxic is set.
- The Docker socket gives the chaos container control of the host's Docker. That is acceptable for a local test tool and is why nothing else receives it.
- A planted log line enters Loki directly instead of going through a service's logger, so it carries no trace ID. That makes it detectable, which is realistic for injected text but worth knowing when grading.
