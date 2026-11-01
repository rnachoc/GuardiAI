/**
 * Tests for the snapshot recorder's bundles: they replay byte for byte, and any tampering, leak or failed
 * ground truth is caught.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import { canonical, holds, readBundleFile, verifyBundle, windowOf, writeBundle } from "../src/recorder.ts";
import { loadScenario } from "../src/scenario.ts";

const scenario = loadScenario(join(dirname(fileURLToPath(import.meta.url)), "..", "scenarios", "payments-latency.yaml"));
const run = { scenario: scenario.id, startedAt: "2026-11-01T12:00:00.000Z", endedAt: "2026-11-01T12:05:00.000Z", events: [], interrupted: false };

/**
 * Builds a small, valid bundle for payments-latency, whose evidence holds.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function files(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    "alert.json.gz": { name: "CheckoutLatencyHigh", firedAt: 1793534490 },
    "metrics.json.gz": { stepSeconds: 15, series: [{ labels: { service_name: "checkout" }, samples: [[1, 0.123456789]] }] },
    "logs.json.gz": { lines: [{ time: "1", service: "checkout", level: "info", traceId: "", line: "Order placed" }] },
    "traces.json.gz": { traces: [] },
    "changes.json.gz": { events: [] },
    "truth/run.json.gz": run,
    "truth/evidence.json.gz": { scenario: scenario.id, evidence: [
      { id: "checkout-client-latency-to-payments", measured: 2.07 },
      { id: "payments-server-latency", measured: 0.072 },
    ] },
    ...overrides,
  };
}

/**
 * Writes a bundle to a new folder and returns the folder.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function written(content = files()): string {
  const folder = mkdtempSync(join(tmpdir(), "bundle-"));
  writeBundle(folder, scenario, content);
  return folder;
}

test("canonical JSON sorts keys and rounds numbers, so equal content gives equal bytes", () => {
  assert.equal(canonical({ b: 1, a: { d: 0.123456789, c: [3, 2] } }), '{"a":{"c":[3,2],"d":0.123457},"b":1}');
});

test("writing the same content twice produces identical files and manifests", () => {
  const first = written();
  const second = written();
  for (const name of ["manifest.json", "metrics.json.gz", "truth/evidence.json.gz"]) {
    assert.deepEqual(readFileSync(join(first, name)), readFileSync(join(second, name)), name);
  }
});

test("a sound bundle verifies, and replays the same content every time", () => {
  const folder = written();
  assert.deepEqual(verifyBundle(folder, scenario), []);
  assert.equal(canonical(readBundleFile(folder, "logs.json.gz")), canonical(readBundleFile(folder, "logs.json.gz")));
});

test("an edited file is caught by its hash", () => {
  const folder = written();
  writeFileSync(join(folder, "logs.json.gz"), gzipSync(Buffer.from(canonical({ lines: [] }))));
  assert.ok(verifyBundle(folder, scenario).some((problem) => problem.includes("does not match its hash")));
});

test("a recording whose evidence fails the ground truth does not verify", () => {
  const folder = written(files({ "truth/evidence.json.gz": { scenario: scenario.id, evidence: [
    { id: "checkout-client-latency-to-payments", measured: 0.4 }, { id: "payments-server-latency", measured: 0.072 }] } }));
  assert.ok(verifyBundle(folder, scenario).some((problem) => problem.includes("fails > 1.2")));
});

test("ground truth that leaks into what the tools serve is caught", () => {
  const folder = written(files({ "changes.json.gz": { events: [{ note: "toxiproxy added guardia-step-0" }] } }));
  assert.ok(verifyBundle(folder, scenario).some((problem) => problem.includes("belongs to the ground truth")));
});

test("assertions compare as written, and a metric that stopped reporting counts as zero", () => {
  assert.equal(holds({ when: "fault", op: ">", value: 1.2 }, 2.07), true);
  assert.equal(holds({ when: "fault", op: "<", value: 0.2 }, 0.3), false);
  assert.equal(holds({ when: "fault", op: "==", value: 0 }, null), true);
});

test("the mid-fault check falls 100 s after the last fault, but before the first revert", () => {
  const window = windowOf(scenario, run);
  assert.equal(window.faultCheck - window.start, 130);
  const decoy = loadScenario(join(dirname(fileURLToPath(import.meta.url)), "..", "scenarios", "decoy-catalog-blip.yaml"));
  const decoyWindow = windowOf(decoy, run);
  assert.equal(decoyWindow.faultCheck - decoyWindow.start, 67);
});

test("a secret recorded in a log line is caught, even though the file is compressed", () => {
  const key = ["AKIA", "Z".repeat(16)].join("");
  const folder = written(files({ "logs.json.gz": { lines: [{ time: "1", service: "checkout", level: "error", traceId: "",
    line: `upload failed with key ${key}` }] } }));
  assert.ok(verifyBundle(folder, scenario).some((problem) => problem.includes("an AWS access key")));
});

