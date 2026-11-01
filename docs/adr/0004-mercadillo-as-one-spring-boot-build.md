# 4. Mercadillo as one Spring Boot build

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

Guardia needs a system to watch that fails in realistic ways: services that call each other over HTTP, share a database server, use a cache and a message broker, and can be slowed, broken or misconfigured one at a time. The brief asks for seven services in Java 21 and Spring Boot 3. Seven separate builds would mean seven Dockerfiles, seven dependency trees to keep in step, and seven Maven runs on every `make up`. Spring Boot 4 has been released, but the brief names Spring Boot 3.

## Decision

- Mercadillo is one Maven build with a module per service, on Spring Boot **3.5.16**, the newest release of the 3.x line, and Java 21.
- One Dockerfile builds every service. Its build stage does not depend on the service, so BuildKit runs Maven once and each service image copies its own jar. Images run as a non-root user on a JRE-only Alpine base, pinned by digest.
- Each service owns a schema in one PostgreSQL server (`catalog`, `inventory`, `checkout`) and migrates it with Flyway. PostgreSQL stays on **17**, because the Flyway that Spring Boot 3.5 manages warns that 18 is untested.
- Services talk through Spring's `RestClient`, with connect and read timeouts from `spring.http.client.*`. The gateway is a small controller that names each public route explicitly instead of Spring Cloud Gateway, so nothing behind it is reachable by accident and no extra framework is needed.
- Checkout never trusts a price from the cart: it prices every order from the catalog. When anything fails after stock is held, it gives the stock back. Inventory and payments are idempotent per order.
- Payments is a fake provider that moves no money. One token, `tok_decline`, is always declined, so refusals are part of normal traffic.
- Every class, method and function names its author in its documentation comment, and `make lint` fails when one does not.

## Consequences

- One build and one image recipe to maintain, and Maven runs once per build.
- Services share a database server, so a slow query in one can be felt by the others. That coupling is realistic, and useful for incidents.
- The explicit gateway needs a code change for every new public route, which is the intended trade-off.
- Unit tests cover each service's logic and HTTP contract without a database. The behaviour against real PostgreSQL, Redis and Kafka is proven on the running stack by `make verify`, not by tests that start containers.
- Moving to Spring Boot 4 later is a new decision record, not an update.
