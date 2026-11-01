---
id: RB-003
title: Queries to PostgreSQL are slow
services: [catalog, inventory, checkout, postgres]
---
# Queries to PostgreSQL are slow

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

1. Find the slow service, then its database spans in traces (`db.system = postgresql`). If every query of one service is slow by the same amount, the connection is slow; if one statement is slow, the query or an index is.
2. Compare services: if only one service's queries are slow, the server itself is healthy, and restarting PostgreSQL would hurt everyone for nothing.
3. Check the connection pool (`db_client_connections_usage` against `db_client_connections_max`). A full pool shows as waiting, not as slow queries.
4. Clearing the catalog cache makes things worse during database latency, because every lookup then reaches the database.
5. If no catalogued action addresses the cause, escalate to a person with the evidence.
