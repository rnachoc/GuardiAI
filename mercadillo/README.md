# Mercadillo

The system Guardia watches: a fictional online marketplace in Java 21 and Spring Boot 3, split into `gateway`, `catalog`, `cart`, `checkout`, `payments` (a fake payment provider), `inventory` and `notifier`, on PostgreSQL, Redis and Kafka, instrumented with OpenTelemetry.

**Status:** not started. Built in Phase 1. Today the stack starts only the backing services, from [`compose.yaml`](../compose.yaml).
