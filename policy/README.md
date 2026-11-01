# Policy and execution service

The service in Java 21 and Spring Boot that decides every proposal against a versioned action catalog, runs approved actions with an idempotency key, verifies recovery, reverts automatically and keeps a hash-chained audit log ([ADR 2](../docs/adr/0002-the-model-proposes-the-code-decides.md)).

**Status:** not started. Built test-first in Phase 5.
