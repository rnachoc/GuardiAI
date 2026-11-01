# Mercadillo

The system Guardia watches: a fictional online marketplace in Java 21 and Spring Boot 3.5, on PostgreSQL, Redis and Kafka, observed with OpenTelemetry ([ADR 4](../docs/adr/0004-mercadillo-as-one-spring-boot-build.md), [ADR 5](../docs/adr/0005-opentelemetry-agent-and-one-collector.md)).

| Service | What it does | Talks to |
| --- | --- | --- |
| `gateway` | The only public entry point, at http://127.0.0.1:8080/api. Forwards each named route to one service | catalog, cart, checkout |
| `catalog` | Products and prices | PostgreSQL (`catalog` schema), Redis cache |
| `cart` | Shopping carts, checked against the catalog | Redis, catalog |
| `checkout` | Prices the cart, holds stock, charges, records the order, announces it | cart, catalog, inventory, payments, PostgreSQL (`checkout`), Kafka |
| `payments` | A fake payment provider. Moves no money; `tok_decline` is always declined | — |
| `inventory` | Stock levels and per-order reservations | PostgreSQL (`inventory`) |
| `notifier` | Confirms each placed order from Kafka. Sending is simulated | Kafka |

A checkout crosses all seven services in a single trace, including the hop over Kafka to the notifier. `load/shoppers.js` keeps steady synthetic traffic flowing ([ADR 6](../docs/adr/0006-k6-for-steady-shoppers.md)).

## Public API

| Method | Route | Answers |
| --- | --- | --- |
| `GET` | `/api/products?category=&limit=` | 200; 400 for a limit outside 1 to 100 |
| `GET` | `/api/products/{sku}` | 200, or 404 |
| `POST` | `/api/carts` | 201 with an empty cart |
| `GET` | `/api/carts/{id}` | 200, or 404 |
| `PUT` | `/api/carts/{id}/items` with `{"sku", "quantity"}` | 200; 400 quantity outside 1 to 20; 404 unknown cart; 422 unknown product or a cart over its limits |
| `DELETE` | `/api/carts/{id}` | 204 |
| `POST` | `/api/checkouts` with `{"cartId", "paymentToken"}` | 201 order; 402 declined; 404 unknown cart; 409 out of stock; 422 empty cart |

## Working on it

`make test-mercadillo` compiles and tests every service in a container. With a local JDK 21 and Maven, `mvn verify` in this folder does the same. The compiler treats every warning as an error.
