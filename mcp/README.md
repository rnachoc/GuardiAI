# Tool servers

GuardiAI's read-only MCP servers, in TypeScript on the official SDK ([ADR 11](../docs/adr/0011-read-only-tool-servers-with-one-grammar-for-both-modes.md)).

**Status: in progress (Phase 3).**

| Server | Tools | State |
| --- | --- | --- |
| `mcp-metrics` | `query_metrics`, `query_metrics_range`, `list_metrics` | Built, live and replay |
| `mcp-logs` | `query_logs` | Built, live and replay |
| `mcp-traces` | `search_traces`, `get_trace`, `critical_path` | Built, live and replay |
| `mcp-changes` | deploy history, git log and diff, flags, config versions | Not built |
| `mcp-runbooks` | hybrid search over runbooks and past incidents | Not built |
| `mcp-actions` | `propose_action`, which only creates a proposal | Comes with the policy engine |

Every tool is read-only. Each server accepts only an allow-listed query subset with caps on time range and result size, and returns text from the system redacted and wrapped as untrusted data.

## Modes

- **Replay** (the default): `GUARDIA_TOOLS_MODE=replay GUARDIA_BUNDLE=../chaos/fixtures/<scenario>`. Answers from a recorded bundle, at the moment its alert fired. It never opens `truth/`.
- **Live**: `GUARDIA_TOOLS_MODE=live`, with `PROMETHEUS_URL`, `LOKI_URL` or `TEMPO_URL` pointing at the stack.

```bash
GUARDIA_BUNDLE=../chaos/fixtures/payments-latency node src/metrics/server.ts
```

The server speaks MCP over stdio. `make test-mcp` runs the tests in a container. They check each grammar's refusals, compare the replayed values with what the real Prometheus recorded, and drive each server end to end over MCP.
