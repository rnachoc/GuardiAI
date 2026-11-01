---
id: RB-002
title: A service stopped answering
services: [gateway, catalog, cart, checkout, payments, inventory, notifier]
---
# A service stopped answering

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

1. A service that died stops reporting: its request rate disappears instead of falling to zero, and its callers log connection errors naming it.
2. Check the callers' errors: checkout failing at the reservation step points at inventory; cart failing to validate products points at catalog.
3. Confirm that no deploy or config change touched the service. If one did, rolling it back comes before restarting.
4. When the process is simply down, restarting that one service is the catalogued action. Restart only the service that is down, never its callers.
