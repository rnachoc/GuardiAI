# Handoff: how to continue GuardiAI locally

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

This is where the cloud sessions stopped, how to pick the work up on your own machine, and everything that remains, phase by phase. [PROGRESS.md](../PROGRESS.md) stays the single source of truth for status; this file is the plan.

## 1. Where things stand

| Phase | Status | Key files |
| --- | --- | --- |
| 0 Foundations | Done | `Makefile`, `.github/workflows/ci.yml`, `tests/` |
| 1 Mercadillo and observability | Done | `mercadillo/`, `deploy/observability/`, `scripts/verify_stack.py` |
| 2 Chaos and snapshots | Done (10 of 40 scenarios) | `chaos/src/`, `chaos/scenarios/`, `chaos/fixtures/` |
| 3 Read-only tool servers | Done | `mcp/src/{metrics,logs,traces,changes,runbooks}/` |
| 4 Investigator | Done, on scripted turns (ADR 16) | `agents/src/`, `agents/responses/` |
| 5 Policy engine | Done as a library | `policy/src/main/java/.../policy/` |
| 6 Remediation and verification | Done; only `restart_service` acts for real | `mcp/src/actions/`, `policy/`, `agents/src/remediate.ts`, `scripts/e2e_remediation.sh` |
| 7 Evaluation | Not started | — |
| 8 Console, postmortem, replay | Not started | — |
| 9 Security pack, model matrix, cost report | Not started | — |
| 10 Release | Not started | — |

Phases 0 to 7 are the minimum publishable version, so phases 6 and 7 come first.

## 2. Set up your machine

You need Docker with Compose, make and git. Everything else runs in pinned containers (ADR 3). Optional, for speed: JDK 21 with Maven 3.9, and Node 22.18 or later.

```bash
git clone https://github.com/rnachoc/GuardiAI.git && cd GuardiAI
make hooks            # pre-commit secret scan
make up               # the whole stack; first run takes a few minutes
make verify           # proves the stack works end to end
make test             # every suite: repo, Mercadillo, policy, chaos, mcp, agents
make lint
```

Grafana is at http://127.0.0.1:3000 and the shop's API at http://127.0.0.1:8080/api/products. `make chaos SCENARIO=payments-latency` runs an incident live.

### Release tags still to create

The cloud session could not push tags. On your machine:

```bash
git fetch origin && git tag -a v0.0.0 5e0cf37 -m "v0.0.0" && git tag -a v0.1.0 482024c -m "v0.1.0" && git tag -a v0.2.0 0ebac97 -m "v0.2.0" && git tag -a v0.3.0 fe9e1e4 -m "v0.3.0" && git push origin --tags
```

Phases 4 and 5 are tagged `v0.4.0` and `v0.5.0` on the commit that marks them done in PROGRESS.md.

### Things only the cloud sandbox needed

You can ignore these on a normal machine. `.env` there carried `GUARDIA_BUILD_NETWORK`, `GUARDIA_BUILD_MAVEN_OPTS` and `GUARDIA_BUILD_TRUSTSTORE` for a TLS-intercepting proxy, and `make test` took `MAVEN_DOCKER_FLAGS` and `NPM_DOCKER_FLAGS` for the same reason. If you work behind a corporate proxy, the comments at the top of `compose.yaml` and the `Makefile` explain them.

## 3. Rules of the project

They are enforced by tests or CI unless noted.

- **Authorship.** Every class, method and function carries `Ignacio Rodriguez Caro <rnachoccontact@gmail.com>` in its doc comment. `make lint` fails otherwise (`scripts/check_authorship.py`). Commits are authored as Ignacio Rodriguez Caro.
- **Pinning.** Every image is pinned by digest (Compose, Makefile, Dockerfile `FROM`). Every GitHub Action is pinned to a commit. Every npm and Maven dependency has an exact version. Tests fail on anything unpinned.
- **Honesty.** No number in a README or report unless it was measured. Write "not measured yet" otherwise. The README and PROGRESS.md must agree on each phase's status, and phases are finished in order; tests enforce both.
- **Decisions.** Every significant choice or dependency gets a numbered ADR in `docs/adr`, listed in its index. Accepted records are never rewritten; a new one supersedes them. There are 16 so far.
- **Tests first** for the policy engine and the executor.
- **Untrusted text.** Anything read from the system (logs, commits, traces, annotations, runbooks) goes through `mcp/src/shared/untrusted.ts` before a model sees it.
- **Ground truth sealed.** Tools may open only the tool-facing files of a bundle. `truth/` is for graders and tests.
- **Before each commit:** `python3 -m unittest discover -s tests`, `make lint`, and the suite of what you touched. Conventional Commits, small and focused. Update PROGRESS.md and the README status table. Mark a phase done only when CI is green.

## 4. Known findings to address

1. **Fixed (ADR 17):** ~~The replay clock is frozen at the alert.~~ It now moves per step, and evidence is pinned to its step. Script or record the remaining decoys the same way. Originally: Replay answers as of the moment the alert fired. A decoy that recovers 10 seconds later, such as `decoy-catalog-blip` (fault from 30 s to 70 s, alert at 60 s), still looks broken to an investigator, so it cannot conclude "recovered, do nothing". Fix: advance the replay clock with the investigation, for example by 30 s per step or by the tools' simulated latency, and record why in an ADR. Then script or record the decoys.
2. **Rates are not extrapolated in replay** (ADR 11). Fine for verdicts. Worth a note in the evaluation report.
3. **No scenario changes deploys, configs or flags yet**, so `mcp-changes` serves empty histories. Phase 2's remaining scenarios fix that (section 5.1).
4. **Most services log nothing per request.** Consider an INFO line per failed downstream call in cart and gateway. That gives investigations log evidence beyond checkout, and needs re-recording the affected bundles.
5. **The Spring Boot dependency** is 3.5.16, as the brief asks. Spring Boot 4 exists. Moving is a new ADR.

## 5. What remains, in order

### 5.1 The other 30 scenarios (Phase 2, needed by Phase 7)

The brief asks for 40 scenarios across 8 families, with 6 decoys and 6 injections. There are 10 now: families latency, availability, database, messaging; 2 decoys; 2 injections. Missing:

- **Fault types to add to the injector** (`chaos/src/stack.ts`, `chaos/src/catalog.ts`, schema in `chaos/src/scenario.ts`):
  - `memory_leak`: a flag that makes one service retain memory. Add a `/chaos/leak` toggle behind a flag in Mercadillo, or a JVM option.
  - `cpu_hog`: a sidecar container that burns CPU next to one service (noisy neighbor).
  - `slow_query`: a Flyway migration that drops `products_category_idx`, as a seeded "bad commit".
  - `pool_exhaustion`: lower `DATABASE_POOL_SIZE` through a config push, or hold connections.
  - `config_push`: write a new config version and restart the service. Record the change.
  - `bad_deploy`: deploy an image built from a seeded commit. Record the deploy.
  - `cert_expiry`: payments answers with a TLS-style error after a date flag.
  - `flag_change`: toggle a feature flag that degrades a path.
- **Supporting systems** the brief names, built inside Mercadillo:
  - a file-based feature-flag service read by every service, for example a mounted `flags.json` polled every few seconds;
  - a deploy-history event log, an append-only JSON Lines file, the one `mcp-changes` live mode already reads through `CHANGES_FILE`;
  - a local git repository of the Mercadillo source whose history contains seeded bad commits, exposed to `mcp-changes` as commits with diffs.
- **The recorder** (`chaos/src/recorder.ts`) must write those change events into `changes.json.gz`. Its leak check must still pass: change events describe what a person did, never the fault's mechanism.
- **Scenario mix to reach:** 8 families (add resources, configuration, deploy, certificates). 4 more decoys (for example a load spike that settles, a restart already finished, a flapping health check, a cron job's burst). 4 more injections, through commit messages, trace attributes (an exception message), a runbook-looking annotation, and a flag description.
- **Then** `make record-all` (about an hour per 10 scenarios) and commit the bundles. `make test` verifies them.

### 5.2 Phase 6: remediation and verification

**Status:** proposals, approvals, execution, verification and the live end-to-end test work (ADR 18). What remains is real runners for the other eight actions and their reverts, wiring the planner after the investigator, a facts collector, and webhook escalation. The numbered plan below is the original; items 1 to 5 are done for `restart_service`.

Acceptance (brief): planner, executor, verification, automatic revert.

1. **Done:** **`mcp-actions`** (`mcp/src/actions/server.ts`) has a single tool, `propose_action({action, target, reason, evidence[]})`. It validates the shape and writes the proposal to a queue, a JSON Lines file or an HTTP POST to the policy service, and returns a proposal ID. It executes nothing. Its tests prove it never touches Docker or any backend.
2. **Partly done:** the service exists, with `POST /proposals` and `GET /audit/verify`, facts read from `guardia.facts.file`. Still to do: approvals, a facts collector, and a Compose service. **Policy service:** wrap `PolicyEngine` and `AuditLog` in Spring Boot (`policy/`, with `spring-boot-starter-web`):
   - `POST /proposals` to judge a proposal;
   - `POST /proposals/{id}/approvals` for people;
   - `GET /audit/verify`.
   - Preconditions are gathered by code: deploy freeze from a file, replicas from Docker, error budget from Prometheus. Never from the proposal's text.
   - Add a Compose service and a healthcheck.
3. **Partly done:** `policy/.../Executor.java` and its 6 tests cover idempotency, dry run, verification polling, revert and escalation behind `Runner` and `Verifier` interfaces. Still to do: the real `Runner` and a Prometheus `Verifier`, and wiring them to `/proposals/{id}/approvals`. **Executor**, test-first:
   - an idempotency key per proposal, claimed in the audit log before running;
   - a dry run first, then the real run through the Docker API for `restart_service` and `scale_deployment` (Compose `--scale`), the flag file for `toggle_flag`, Redis for `clear_cache`, Kafka consumer pause and resume for the notifier, an env change plus restart for `increase_pool_size`, and a simulated standby for `failover`;
   - after running, poll the action's verification query until it holds or times out;
   - if it never holds, run the action's auto-revert and escalate (webhook mock, section 5.4).
   - Every step goes to the audit log.
   - The executor runs with the Docker socket. Nothing else does.
4. **Partly done:** the gate in `agents/src/planner.ts` (catalog only, allowed targets, real evidence, nothing for `none`, submitted through `mcp-actions`) and its 4 tests. Still to do: scripted drafting turns, and wiring the planner after the investigator. **Remediation planner** (`agents/src/planner.ts`): turns a validated diagnosis into proposals from the catalog only, each with its expected effect, verification query and rollback. Scripted turns, as in ADR 16.
5. **End-to-end test:**
   1. run `inventory-killed` live;
   2. the planner proposes `restart_service:inventory`;
   3. in suggest mode, the proposal waits for approval;
   4. after approval, the executor restarts inventory, verification passes, and the audit chain verifies;
   5. a second scenario forces verification to fail, and the action is reverted.
   Add this to CI's Stack job if it fits in time.
6. **ADRs:** execution and idempotency; the auto-revert and escalation contract.

### 5.3 Phase 7: evaluation

Acceptance (brief): 40 scenarios, three baselines, graders, CI gate, generated report.

1. **Harness** (`evals/`, TypeScript, next to `agents/`): run each scenario N times (default 3) in replay. Store each run's ledger, diagnosis, proposals and decisions.
2. **Graders**, deterministic first:
   - root cause top-1 and top-3, where component and mechanism match `groundTruth.rootCause`;
   - evidence validity, by re-running the stored calls, as the validator already does;
   - safety: any forbidden action proposed, any action over the blast radius, any successful injection (a proposal or a top hypothesis matching what the planted text asked), any secret in an output;
   - decoys: correct when there is no action and a clear explanation;
   - cost and speed: steps, tokens, latency, simulated time to mitigation.
3. **LLM judge** for equivalent free-text mechanisms. It needs a model and is therefore "not measured" under ADR 16. Build the 40 human labels anyway (`evals/labels.yaml`), so the judge can be calibrated with Cohen's kappa when a key exists.
4. **Baselines:**
   - (a) a rule-based alert-to-action map, for example `CheckoutErrorsHigh` → `restart_service:checkout`;
   - (b) a single prompt with no tools, which needs a model and is "not measured";
   - (c) full GuardiAI.
   (a) and (c) run for free.
5. **`evals/thresholds.yaml`** and a CI job that fails on regression, and fails when `evals/REPORT.md` is not what the harness generates (the same pattern as the dashboard test).
6. **`evals/REPORT.md`** is generated. It is honest: every failure is listed with its scenario, and everything model-dependent is marked "not measured". pass@k and pass^k are reported over the N runs.
7. **Model matrix:** "not measured" under ADR 16. The harness supports it, with a provider per tier behind the spend cap.

### 5.4 Phase 8: console, postmortem, replay

1. **Console** (`console/`, React, Vite, TypeScript), in the style you asked for: Apple's and Netflix's sites. Dark, generous whitespace, large type, one accent colour, smooth transitions, rows of incident cards. Views:
   - live incident timeline;
   - hypotheses with links to their evidence (open the ledger entry and re-run it);
   - approval queue with expected effect and blast radius (call the policy service);
   - replay of any recorded incident with its full tool trace;
   - cost per incident ("not measured" for model cost under ADR 16).
   Serve it from Compose behind a strict content security policy, with no tokens in local storage.
2. **Notifications** go to a Slack-like webhook mock, a small Compose service that stores and shows messages.
3. **Postmortem writer** (`agents/src/postmortem.ts`): builds a timeline from the audit log and writes a blameless postmortem that cites evidence IDs. It suggests runbook edits as a diff against `docs/runbooks/`, and the result goes to `docs/incidents/` in the runbook format, so `mcp-runbooks` finds it. Scripted turns under ADR 16.

### 5.5 Phase 9: security pack, model matrix, cost report

1. **Security tests** with a README table (risk, what stops it, where) for each risk the brief lists:
   - prompt injection through logs, commits, trace attributes and annotations;
   - expensive or abusive queries;
   - secret leakage through logs;
   - chaining actions outside the catalog;
   - a confused deputy, where an agent names another service to act on;
   - runaway loops;
   - budget exhaustion;
   - audit log tampering.
   Most already have a test; the table in the README lists them. Add the missing ones: trace attribute injection, a commit message injection end to end, and the confused deputy through `mcp-actions`.
2. **Model matrix and cost report:** "not measured" under ADR 16. Write the report with the harness ready, so a key fills it.
3. **GuardiAI's own observability:** instrument the agents with OpenTelemetry's GenAI semantic conventions, and add a Grafana dashboard for time to diagnosis, steps, tokens, cost per incident, evidence-validity rate and unsafe-proposal count.

### 5.6 Phase 10: release

1. **Static public demo** on GitHub Pages: a build of the console in replay-only mode, reading the bundles and recorded investigations, with no backend. This is the page anyone can open in a browser. Add a Pages workflow, with its actions pinned to commits.
2. **A three-minute video script**, a one-page case study with real numbers and one failure explained (the frozen replay clock, or the consumer lag that read zero, are good candidates), and the final ADRs.
3. **README:** what it is, a screenshot, a one-command start, the status table and the risks table, as the brief's definition of done asks.
4. **Optional:** Helm and kind (`deploy/`).

## 6. Suggested first session

1. Create the release tags (section 2).
2. Add turns for `decoy-notifier-hiccup`, now that the replay clock moves (ADR 17).
3. Build the Spring Boot policy service, then the executor test-first (section 5.2). `mcp-actions` is already built.
