# 22. GuardiAI observes itself, without an SDK

- **Status:** Accepted
- **Date:** 2026-11-02
- **Author:** Ignacio Rodriguez Caro

## Context

The brief asks for GuardiAI's own observability, with OpenTelemetry's GenAI conventions: time to diagnosis, steps, tokens, cost per incident, evidence-validity rate and unsafe-proposal count. Two parts emit it: the policy service, which decides and acts, and the agents, which investigate in short-lived processes. Adding the OpenTelemetry SDK to the agents means new dependencies to pin and audit, for a handful of points per run.

## Decision

- The policy service serves `/metrics` in Prometheus's text format, counted from its audit log: proposals, verdicts, approvals, executions, verifications, reverts, escalations, and whether the chain is intact. The audit log is the record of what happened, so the metrics cannot disagree with it. Prometheus scrapes it.
- The agents send one OTLP/HTTP JSON payload of traces and one of metrics per investigation, with `fetch`, to the Collector that is already in the stack. The trace is an `invoke_agent` span with an `execute_tool` span per ledger entry. The metrics are `gen_ai.client.token.usage`, `gen_ai.client.operation.duration`, `guardia.investigations` (by evidence validity) and `guardia.investigation.steps`.
- Every metric is cumulative, because Prometheus rejects delta temporality over OTLP. Each investigation has its own `service.instance.id`, so each is its own series, and the dashboard aggregates them with `max_over_time` over the time range.
- Telemetry is sent only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set, and a collector that is down never fails an investigation.

## Consequences

- The Grafana dashboard has a GuardiAI row (judged, unsafe, verified, reverts, escalations, audit chain) and a GuardiAI agents row (investigations, evidence validity, steps, tokens, time to diagnosis). `make verify` checks that all of them return data, and CI sends one investigation first.
- Cost per incident is not shown: with the fake provider there is no cost, and a figure computed from scripted token counts would describe nothing (ADR 16).
- The hand-written exporter covers only what GuardiAI sends. If the agents grow, the SDK becomes the better choice.
