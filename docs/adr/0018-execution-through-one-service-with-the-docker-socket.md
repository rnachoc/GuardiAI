# 18. Execution through one service with the Docker socket

- **Status:** Accepted
- **Date:** 2026-11-02
- **Author:** Ignacio Rodriguez Caro

## Context

Phase 6 makes approved proposals change the real system. Whatever can change the system is the most dangerous part of GuardiAI, so the power to act must sit in exactly one place, behind the policy engine. It must never claim a success it cannot show.

## Decision

- The policy service is the only component with the Docker socket. The agents, the tool servers and `mcp-actions` never receive it.
- The policy service judges a proposal (`POST /proposals`), records distinct approvers (`POST /proposals/{id}/approvals`), and executes (`POST /proposals/{id}/execute`). The executor refuses unless the engine approved, or enough people did. It claims the proposal's idempotency key in the audit log, dry-runs, runs, and then verifies.
- `DockerRunner` implements only `restart_service`, by restarting the Compose service's container over the Docker API. Every other action fails its dry run, so nothing is reported as run that did not run.
- Verification asks Prometheus. An empty result counts as zero, and an error never counts as recovered. The first check waits a **settle period** (70 s), long enough for the longest range in a verification query to hold only data from after the action. The end-to-end test found the need for it: without it, a killed service's samples from before the kill made its one-minute rate look healthy.
- Preconditions come from a facts file. For the local stack it is a static, committed file (`deploy/policy/facts.json`), honest about which facts are not available; a collector is future work.
- `make e2e-remediation` proves the chain on the running stack, and CI runs it. Inventory is killed; a restart is proposed and refused until approved. It is executed once, really restarted, and verified. The audit chain stays intact.

## Consequences

- One service, running as root with the socket, can control the host's Docker. That is acceptable for a local, fictional stack and is why nothing else has the socket.
- Every remediation takes at least the settle period before it is confirmed. A slower confirmation is preferred to a false one.
- Other actions (scale, rollback, flags, cache, consumers, pool, failover) and their reverts are designed in the catalog and covered by the executor's tests, but have no real runner yet.
