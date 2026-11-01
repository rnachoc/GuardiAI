# 11. Read-only tool servers with one grammar for both modes

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

The agents investigate through tools, and the tools are where an agent's mistakes or a manipulated agent's intentions meet the system. A query language exposed as-is lets an agent run expensive queries, read anything the backend holds, or have text from the observed system read back as if it were trustworthy. Evaluations also need the same tools to answer from recordings, so a tool must behave the same way live and in replay.

## Decision

- Each tool server is a TypeScript MCP server on the official SDK (`@modelcontextprotocol/sdk` 1.32.2, with zod 4.6.7). Every tool is annotated read-only and non-destructive, and none can write anywhere.
- **mcp-metrics** accepts an allow-listed subset of PromQL. That subset covers selectors with `= != =~ !~`, `rate` and `increase`, `sum avg min max count by`, `histogram_quantile`, `vector`, arithmetic and `or`. Limits:
  - ranges up to 10 minutes;
  - at most 1000 characters and 60 parts per query;
  - regular expressions of at most 100 characters, with no repeated group;
  - range queries of at most 2 hours and 240 points;
  - at most 50 series per answer, truncated with a flag.
  Live mode validates with the same parser before forwarding to Prometheus. Replay evaluates the subset over the recorded series.
- **mcp-logs** accepts a LogQL subset: a selector on `service_name` or `service_namespace`, line filters, and a filter on `detected_level` or `trace_id`. It returns at most 200 lines from at most 1 hour.
- **mcp-traces** takes structured search parameters instead of raw TraceQL, returns a trace span by span (at most 300), and summarises its critical path.
- Every log line and free-text span attribute is redacted (keys, tokens, passwords, emails, card numbers), wrapped in `<<<untrusted … untrusted>>>` delimiters that a line cannot close itself, and flagged when it matches a known injection pattern.
- Replay opens only the tool-facing files of a bundle, after checking their hashes. It refuses `truth/` by name, and its clock is the moment the alert fired.

## Consequences

- The same queries are accepted or refused in both modes, so an evaluation in replay measures the behaviour that live mode would have.
- Replay rates take the first and last sample in the range without Prometheus's extrapolation, and gauges are read at 15-second steps. Across every metric in the recorded bundles, replay reached the same verdict as the real Prometheus. Most values were within 1%; the widest gaps were a rate at 1.0 against 1.28 and a fast-growing gauge at 108 against 127.
- Agents cannot use the rest of PromQL, LogQL or TraceQL. A function the subset lacks is refused by name, with the list of what is allowed.
- `mcp-changes`, `mcp-runbooks` and `mcp-actions` follow the same rules when they are built.
