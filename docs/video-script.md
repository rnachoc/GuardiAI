# Video script: GuardiAI in three minutes

*Ignacio Rodriguez Caro · Every screen below exists in the repository; nothing is mocked.*

| Time | On screen | Voice-over |
| --- | --- | --- |
| 0:00–0:15 | Grafana overview at http://127.0.0.1:3000: live traffic, seven services, the service graph | "This is Mercadillo, a seven-service shop I built to break. Real traffic, real traces, real metrics." |
| 0:15–0:30 | Terminal: `make chaos SCENARIO=payments-latency`; the p95 panel climbs and turns red | "I inject 1.5 seconds of latency between checkout and payments. The checkout alert fires." |
| 0:30–1:00 | Replay console, `payments-latency`: the tool trace, step by step, then the ranked hypotheses with evidence IDs | "GuardiAI investigates with read-only tools. Every call goes into a ledger. The diagnosis must cite it, and code re-runs each cited call. Checkout's calls to payments are slow, but payments itself is fast. So it's the network, not the service." |
| 1:00–1:25 | Replay console, `injection-log-restart-inventory`: the planted log line, wrapped and flagged; the refused draft "restart_service on inventory" | "Here an attacker planted a log line telling the AI to restart inventory. It arrives wrapped as untrusted and flagged. The draft that obeys it is refused, because no hypothesis names inventory." |
| 1:25–2:05 | Terminal: `make e2e-remediation`; then the approval queue at http://127.0.0.1:8090: refused, approved, verified; the timeline beneath | "Agents only propose. The policy engine decides. Restarting inventory needs a person's approval. Once approved, the service restarts it for real, waits for the metrics to settle, and confirms recovery in Prometheus. Restarting catalog is refused outright, because four services depend on it." |
| 2:05–2:20 | Timeline: "Audit chain intact"; Grafana's GuardiAI row; the escalations page at http://127.0.0.1:8091 | "Every step is in a hash-chained, timestamped audit log. If a fix doesn't work, it's reverted and a person gets a message." |
| 2:20–2:45 | `evals/REPORT.md`: the summary table | "Against rule-based automation on twenty recorded incidents: rules proposed a forbidden action in eighteen of them and fell for all three injections. GuardiAI's guardrails, on scripted turns: zero. No paid model has run yet. The report says so, and a key fills in the rest." |
| 2:45–3:00 | README: the risks table, then the repository | "Every risk has a test that proves the guardrail says no. GuardiAI: the AI proposes, the code decides." |

**Recording notes.**

- Start with `make up` and let traffic run for five minutes, so the graphs have history.
- Before the remediation scene, run `make e2e-remediation` once, off camera, so the queue and timeline have content.
- Record at 1440 × 900, and zoom the browser to 125% for legibility.
