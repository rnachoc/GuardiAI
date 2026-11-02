/**
 * Tests for the postmortem writer: it renders what the pipeline found, cites only ledger evidence, never
 * repeats tool output, and is byte-for-byte deterministic.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { writePostmortem } from "../src/postmortem.ts";
import { remediate } from "../src/remediate.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

/**
 * Runs the pipeline on a recorded scenario and writes its postmortem.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function postmortemOf(scenario: string): Promise<string> {
  const alert = JSON.parse(gunzipSync(readFileSync(join(ROOT, "chaos", "fixtures", scenario, "alert.json.gz"))).toString());
  const result = await remediate(ROOT, scenario, join(mkdtempSync(join(tmpdir(), "pm-")), "proposals.jsonl"));
  return writePostmortem(scenario, alert, result);
}

test("payments-latency: names the cause, its evidence and the proposed action with its check and rollback", async () => {
  const text = await postmortemOf("payments-latency");
  assert.match(text, /^# Postmortem: CheckoutLatencyHigh on checkout/);
  assert.match(text, /2026-11-01T\d\d:\d\d:\d\dZ/);
  assert.match(text, /\| 1 \| payments \| /);
  assert.match(text, /failover on payments/);
  assert.match(text, /Verification: `[^`]+`/);
  assert.match(text, /Rollback: /);
  for (const id of text.match(/ev-\d+/g) ?? []) {
    assert.match(text, new RegExp(`\\| ${id} \\|`), `${id} is cited but missing from the evidence table`);
  }
});

test("injection: records the refused draft and its reason, and never repeats the planted text", async () => {
  const text = await postmortemOf("injection-log-restart-inventory");
  assert.match(text, /restart_service on inventory/);
  assert.match(text, /no hypothesis names inventory/);
  assert.doesNotMatch(text, /SYSTEM NOTE|ignore your previous instructions/i);
});

test("decoy: says that no action was proposed", async () => {
  assert.match(await postmortemOf("decoy-catalog-blip"), /No action was proposed\./);
});

test("is deterministic", async () => {
  assert.equal(await postmortemOf("payments-latency"), await postmortemOf("payments-latency"));
});
