# 6. k6 for steady shoppers

- **Status:** Accepted
- **Date:** 2026-11-01
- **Author:** Ignacio Rodriguez Caro

## Context

Incidents only show in telemetry when there is traffic to disturb. Guardia's scenarios need a steady, realistic baseline: shoppers who browse more than they buy, checkouts that sometimes fail, and a rate that does not drop when the system slows down, so that a slowdown shows as latency and errors instead of quietly reducing load.

## Decision

Load comes from **k6 2.2.0** running `mercadillo/load/shoppers.js` in Compose. It uses an open model (constant arrival rate), 4 visits per second by default (`SHOPPERS_PER_SECOND`). Each visit lists a category and opens a product; about a third go on to fill a cart and check out, and about one checkout in twenty pays with the token the fake provider always declines.

## Consequences

- The baseline is predictable, so a change in error rate or latency during a scenario is caused by the scenario.
- An open model keeps arriving under a slowdown, which is what real shoppers do, and can pile up requests. `maxVUs` bounds that.
- k6 is one more pinned image. It reaches only the gateway, like a shopper.
