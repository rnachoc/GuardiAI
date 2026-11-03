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

test("with the audit log, the postmortem tells what the policy service did, for these proposals only", async () => {
  const alert = JSON.parse(gunzipSync(readFileSync(join(ROOT, "chaos", "fixtures", "payments-latency", "alert.json.gz"))).toString());
  const result = await remediate(ROOT, "payments-latency", join(mkdtempSync(join(tmpdir(), "pm-")), "proposals.jsonl"));
  const id = result.proposalIds[0]!;
  const audit = [
    { sequence: 1, at: "2026-11-01T14:40:00Z", kind: "proposal", data: { id, action: "failover", target: "payments" } },
    { sequence: 2, at: "2026-11-01T14:40:00Z", kind: "decision", data: { proposal: id, verdict: "NEEDS_APPROVAL", reasons: ["high risk, every check passed"] } },
    { sequence: 3, at: "2026-11-01T14:41:10Z", kind: "approval", data: { proposal: id, by: "on-call" } },
    { sequence: 4, at: "2026-11-01T14:41:12Z", kind: "approval", data: { proposal: "prop-someone-else", by: "x" } },
    { sequence: 5, at: "2026-11-01T14:41:20Z", kind: "execution", data: { proposal: id, outcome: "ran", action: "failover", target: "payments" } },
    { sequence: 6, at: "2026-11-01T14:42:40Z", kind: "verification", data: { proposal: id, outcome: "verified", poll: 1 } },
  ];
  const text = writePostmortem("payments-latency", alert, result, audit);
  assert.match(text, /## Timeline/);
  assert.match(text, /\| 14:40:00 \| decision \| needs approval: high risk, every check passed \|/);
  assert.match(text, /\| 14:41:10 \| approval \| approved by on-call \|/);
  assert.match(text, /\| 14:42:40 \| verification \| recovery verified \|/);
  assert.doesNotMatch(text, /approved by x/);
  assert.doesNotMatch(writePostmortem("payments-latency", alert, result), /## Timeline/);
});
