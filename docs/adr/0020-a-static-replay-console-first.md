# 20. A static replay console first

- **Status:** Accepted
- **Date:** 2026-11-02
- **Author:** Ignacio Rodriguez Caro

## Context

The brief's console (Phase 8) has a live incident timeline, hypotheses linked to evidence, an approval queue, replay with full tool traces, and cost per incident, in React or Angular. Its public demo (Phase 10) replays recorded incidents with no backend. The owner could not see anything of the project, and wanted the style of Apple's and Netflix's sites. Phase 7 is blocked on recording 30 more scenarios, which takes hours of wall-clock time.

## Decision

- The replay part comes first, as one static page built from the recordings: `make console` writes `console/dist/index.html`, with its data inlined, no framework and no server. It is the seed of the Phase 10 demo, and `.github/workflows/pages.yml` publishes it to GitHub Pages once Pages is enabled.
- For each recorded scenario it shows:
  - the alert, the timeline of faults, and the key metric over the run, evaluated with the replay's own PromQL;
  - the logs' warnings and errors, with untrusted lines flagged;
  - the ground truth, labelled as sealed from the agents;
  - the rule baseline's action.
  Where scripted turns exist, it adds GuardiAI's evidence ledger, the diagnosis with each citation linked to its tool call, and the plan with what the gate refused.
- Its look is dark and cinematic: system type in the manner of Apple's sites, sideways rows of cards in the manner of Netflix's.
- The live console stays in Phase 8: the approval queue against the policy service, the live timeline, notifications, and the React build the brief names.

## Consequences

- Anyone can open the page in a browser and follow an incident from alert to proposal, with every claim one click from its evidence.
- Phase 8 is started before Phase 7 is done. PROGRESS.md says so, and neither phase is marked done out of order.
- A static page cannot approve or execute; it only replays.
