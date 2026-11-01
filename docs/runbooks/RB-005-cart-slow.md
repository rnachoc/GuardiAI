---
id: RB-005
title: Cart operations are slow
services: [cart, redis, checkout]
---
# Cart operations are slow

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

1. Every cart operation reads or writes Redis, so a slow cart with slow Redis spans (`db.system = redis`) points at the connection to Redis.
2. Checkout slows down with the cart, because it reads the cart first. Do not chase checkout.
3. The catalog cache is a different Redis use. Clearing it does not help the cart.
4. If no catalogued action addresses the cause, escalate to a person with the evidence.
