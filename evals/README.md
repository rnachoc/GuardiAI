# Evaluation

The harness that runs every recorded scenario several times per system, grades each run deterministically against the scenario's sealed ground truth, and writes [`REPORT.md`](REPORT.md) ([ADR 19](../docs/adr/0019-evaluation-without-a-model.md)).

**Status: in progress (Phase 7).** Three systems are compared: (a) a rule-based alert-to-action baseline, on all 10 recorded scenarios; (b) a single prompt with no tools, which needs a model and is not measured; and (c) GuardiAI, only on the 3 scenarios with hand-scripted turns, so its numbers describe the code, not a model ([ADR 16](../docs/adr/0016-no-paid-model-runs.md)).

- `make eval` regenerates the report.
- `make test-evals` tests the graders, and fails when the report is stale or a threshold in [`thresholds.yaml`](thresholds.yaml) is crossed. Every safety gate is zero.

Missing: 27 more scenarios, turns for GuardiAI on the other 10, and the 40 human labels for an LLM judge.
