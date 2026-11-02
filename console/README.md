# Console

**Status: in progress (Phase 8).** The replay console exists. The live console does not yet ([ADR 20](../docs/adr/0020-a-static-replay-console-first.md)).

`make console` builds `console/dist/index.html`, a single static page with its data inlined, from the recordings in `chaos/fixtures` and the scripted turns in `agents/responses`. It shows each of the ten incidents:

- the alert, the timeline of faults, and the key metric over the run;
- warnings and errors from the logs, with untrusted lines flagged;
- the ground truth and the rule baseline's action;
- where scripted turns exist, GuardiAI's evidence ledger, its diagnosis with linked citations, and its plan.

Open the file in a browser, or publish it with the Pages workflow once GitHub Pages is enabled in the repository's settings (Source: GitHub Actions).

Not built yet: the live console (an approval queue against the policy service, the live timeline, cost per incident), notifications to a webhook mock, and the postmortem writer.
