/**
 * Tests for the run plan and the toxics it sends to Toxiproxy.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { loadScenario } from "../src/scenario.ts";
import { plan } from "../src/timeline.ts";
import { toxicFor } from "../src/stack.ts";

const SCENARIOS = join(dirname(fileURLToPath(import.meta.url)), "..", "scenarios");

test("a fault with no revert time is reverted at the end of the scenario", () => {
  const actions = plan(loadScenario(join(SCENARIOS, "payments-latency.yaml")));
  assert.deepEqual(actions.map((a) => [a.atSeconds, a.action]), [[30, "apply"], [300, "revert"]]);
});

test("a decoy's fault is reverted at its own revert time, long before the end", () => {
  const actions = plan(loadScenario(join(SCENARIOS, "decoy-catalog-blip.yaml")));
  assert.deepEqual(actions.map((a) => [a.atSeconds, a.action]), [[30, "apply"], [70, "revert"]]);
});

test("a planted log is applied once and never reverted, and actions stay in time order", () => {
  const actions = plan(loadScenario(join(SCENARIOS, "injection-log-restart-inventory.yaml")));
  assert.deepEqual(actions.map((a) => [a.atSeconds, a.action, a.fault.type]),
    [[30, "apply", "latency"], [60, "apply", "plant_log"], [300, "revert", "latency"]]);
});

test("latency and timeout faults become the matching Toxiproxy toxics", () => {
  assert.deepEqual(toxicFor({ type: "latency", proxy: "checkout-payments", latencyMs: 1500, jitterMs: 200 }, 0),
    { name: "guardia-step-0", type: "latency", stream: "downstream", toxicity: 1, attributes: { latency: 1500, jitter: 200 } });
  assert.deepEqual(toxicFor({ type: "timeout", proxy: "checkout-payments", afterMs: 0 }, 2).attributes, { timeout: 0 });
  assert.throws(() => toxicFor({ type: "kill", service: "inventory" }, 0), /not a toxic/);
});
