# 3. Docker and make are the only prerequisites

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodríguez Caro

## Context

The project is done when `make up && make test` works on a clean machine with no API key. Guardia spans Java, TypeScript, an observability stack and several linters. Asking a reader to install the right version of each one is the most common reason a portfolio project fails to start on someone else's machine, and a tool that differs between a laptop and CI produces results that cannot be compared. The project also handles fictional credentials next to real code, and a real secret pushed by mistake stays in the git history forever.

## Decision

- Docker with Compose, and make, are the only tools a machine needs. Every service runs from Compose, and every check (tests, linters, the secret scan) runs in a container started by a make target.
- Every image is pinned by tag and digest, in `compose.yaml` for services and in the `Makefile` for tools, which is the only place that names a tool image. Every GitHub Action is pinned to a full commit. A test fails if anything is left unpinned.
- CI calls the same make targets a developer does, so the two cannot drift apart.
- Secrets are scanned with [gitleaks](https://github.com/gitleaks/gitleaks) (MIT licence), pinned at 8.30.1: the whole history in CI, and the staged changes in a pre-commit hook that `make hooks` installs. Both run through the same make targets and the same image.
- Compose comes first. Helm and kind are an optional last phase, as the brief allows.

## Consequences

- A clone needs nothing but Docker and make to start the stack and run the checks. Local toolchains remain optional for working faster in one component.
- Container start-up adds a second or two to each check, and the first run downloads the images.
- Pinned versions do not update themselves. Dependabot proposes updates for the actions and the Compose images, while the tool images in the `Makefile` are updated by hand.
- The pre-commit hook needs Docker running, or a local gitleaks set with `GITLEAKS=gitleaks`. When neither is available it stops the commit with an explanation instead of passing silently.
