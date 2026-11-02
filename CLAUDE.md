# GuardiAI: working rules

Read [`docs/HANDOFF.md`](docs/HANDOFF.md) first: it says what exists, what is left, and how to do each piece.

## Language and authorship

- Everything in the repository is in English: code, comments, docs, commit messages. Talk to the owner in Spanish from Spain.
- Commits are authored by `Ignacio Rodriguez Caro <58845377+rnachoc@users.noreply.github.com>`.
- Every class, method and function carries a doc comment naming its author: `Ignacio Rodriguez Caro <rnachoccontact@gmail.com>`. `make lint` fails otherwise (`scripts/check_authorship.py`).
- The product is "GuardiAI" in prose; technical identifiers stay lowercase `guardia`.

## How to work

- Docker and make are the only prerequisites. Images are pinned by digest; keep them pinned.
- Tests first: commit the failing test (`test(...)`), then the code that passes it (`feat(...)` or `fix(...)`), then the docs (`docs: ...`).
- Before every commit: `make lint` and the test suite of what you touched (`make test-policy`, `make test-agents`, `make test-chaos`, ...). `make test` runs all of them.
- A new scenario is committed together with its recording (`make record SCENARIO=<id>`); `make test-chaos` fails on a scenario without one.
- After changing scenarios or recordings, regenerate `evals/REPORT.md` with `make eval`; CI fails when it is out of date.
- No paid model calls (ADR 16). The fake provider and scripted turns cost nothing.
- Never claim a number that was not measured. Say "not measured" instead.
- Keep `PROGRESS.md`, the README status table and `docs/HANDOFF.md` true after every change.

## Useful commands

| Command | What it does |
| --- | --- |
| `make up` / `make down` | Start or stop the stack |
| `make smoke`, `make verify` | Check the running stack |
| `make e2e-remediation` | Kill inventory, approve, execute, verify recovery |
| `make e2e-actions` | Fail payments over, clear the cache, pause and resume the notifier (once an hour) |
| `make record SCENARIO=<id>` | Run a scenario and record it (about 7 minutes) |
| `make eval` | Regenerate the evaluation report |
| `make console` | Build the replay console into `console/dist/` |
| `make postmortem SCENARIO=<id>` | Print a postmortem |
