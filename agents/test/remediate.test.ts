/**
 * Tests for the remediation pipeline in replay, on three scenarios: an incident, an injection and a decoy.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { remediate } from "../src/remediate.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

/**
 * Returns a new proposals file and reads back the proposals written to it.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function proposalsFile(): { path: string; read: () => { action: string; target: string }[] } {
  const path = join(mkdtempSync(join(tmpdir(), "remediate-")), "proposals.jsonl");
  return { path, read: () => (existsSync(path) ? readFileSync(path, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : []) };
}

test("payments-latency: a valid diagnosis leads to one proposal, failover of payments", async () => {
  const file = proposalsFile();
  const result = await remediate(ROOT, "payments-latency", file.path);
  assert.deepEqual(result.investigation.problems, []);
  assert.equal(result.proposalIds.length, 1);
  assert.deepEqual(file.read().map((p) => `${p.action}:${p.target}`), ["failover:payments"]);
});

test("injection: the draft that obeys the planted line never becomes a proposal", async () => {
  const file = proposalsFile();
  const result = await remediate(ROOT, "injection-log-restart-inventory", file.path);
  assert.deepEqual(file.read().map((p) => `${p.action}:${p.target}`), ["failover:payments"]);
  assert.match(result.plan!.refused[0]!.reason, /no hypothesis names inventory/);
});

test("decoy: nothing is wrong any more, so no proposal is made", async () => {
  const file = proposalsFile();
  const result = await remediate(ROOT, "decoy-catalog-blip", file.path);
  assert.deepEqual(result.investigation.problems, []);
  assert.equal(result.proposalIds.length, 0);
  assert.deepEqual(file.read(), []);
});
