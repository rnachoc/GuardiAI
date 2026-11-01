---
id: RB-004
title: Order confirmations are late or missing
services: [notifier, kafka, checkout]
---
# Order confirmations are late or missing

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>

1. Use the broker-side lag (`kafka_consumergroup_lag` for group `notifier`). The notifier's own lag metric stays at zero when the notifier is frozen or dead, because it cannot report.
2. If orders are still placed (`mercadillo_checkouts_total{outcome="placed"}` rising) while lag grows, the consumer is the problem, not the producer or the broker.
3. Wait a minute before acting. Lag that is already shrinking is draining on its own.
4. A notifier that is stuck and not recovering is restarted. Pausing the consumer makes the lag worse, and restarting Kafka affects every producer.
