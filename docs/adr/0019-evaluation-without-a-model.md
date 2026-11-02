# 19. Evaluation without a model

- **Status:** Accepted
- **Date:** 2026-11-02
- **Author:** Ignacio Rodriguez Caro

## Context

The brief's evaluation runs every scenario several times per system, grades runs deterministically first, gates the build on safety, compares three baselines and a model matrix, and publishes an honest report. No model may be called ([ADR 16](0016-no-paid-model-runs.md)), so part of that cannot be measured. The brief is explicit that an unmeasured number is written as "not measured".

## Decision

- `evals/` runs each recorded scenario N times (3 by default) through:
  - (a) a rule-based alert-to-action baseline;
  - (c) GuardiAI's full pipeline in replay: investigation, planner gate and `mcp-actions`.
  GuardiAI runs only where hand-scripted turns exist. (b), the single prompt without tools, needs a model and is "not measured".
- The graders are deterministic:
  - root cause top-1 and top-3 (component and mechanism);
  - whether only acceptable actions were proposed;
  - evidence validity, from the investigator's validator;
  - decoys handled with no action;
  - and the safety gates: forbidden actions proposed, actions over the blast radius (from the policy engine's own graph and catalog), successful injections, and secrets in outputs.
- `evals/thresholds.yaml` holds the gates. Every safety gate must be zero for GuardiAI. CI fails when one is crossed, and when `evals/REPORT.md` differs from what the harness generates. The report has no dates or timings, so it is reproducible.
- The report's first lines say that GuardiAI's results describe the code on scripted turns, not a model's ability.

## Consequences

- Safety regressions in the tools, the validator, the planner gate or the catalog fail the build.
- The comparison with the rule baseline is real for the scenarios GuardiAI ran: the baseline proposed forbidden actions on most scenarios and fell for both injections. GuardiAI's own numbers are not evidence of model quality.
- Calibrating an LLM judge against human labels and the model matrix wait for model runs. The harness has a place for them.
