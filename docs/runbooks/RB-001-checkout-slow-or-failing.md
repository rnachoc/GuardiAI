---
id: RB-001
title: Checkout is slow or failing
services: [checkout, payments, inventory, cart, catalog]
---
# Checkout is slow or failing

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

Checkout calls cart, catalog, inventory and payments in that order, so its latency and errors are usually someone else's.

1. Compare checkout's server latency with its client latency to each dependency (`http_client_request_duration_seconds` by `server_address`). The dependency whose client latency matches checkout's is the suspect.
2. Compare that client latency with the dependency's own server latency. If the dependency is fast on its side, the time is lost on the way, on the network or in a proxy, not inside the dependency.
3. Look at the critical path of a slow checkout trace to confirm which call dominates.
4. A 502 from checkout means a dependency did not answer in time. The log line names the call that failed.
5. Restarting checkout does not fix a slow dependency. If payments is unreachable or slow and the fault is not inside checkout, failing over payments is the catalogued action.
