/**
 * Tests for the planner's gate: catalog actions on allowed targets with real evidence pass and reach
 * mcp-actions; everything else is refused with its reason, and a decoy admits no proposal.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createActionsServer } from "../../mcp/src/actions/server.ts";
import { investigate, type Diagnosis, type Tool } from "../src/investigator.ts";
import { loadCatalog, plan, submit, type Draft } from "../src/planner.ts";
import { FakeProvider } from "../src/providers.ts";
import { defaultRunbooks, replayTools } from "../src/tools.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const CATALOG = loadCatalog(join(ROOT, "policy", "src", "main", "resources", "catalog-v1.yaml"));
const FAILOVER: Draft = { action: "failover", target: "payments", reason: "the path to payments is slow",
  evidence: ["ev-2"], expectedEffect: "checkout p95 back under 1 second" };

/**
 * Runs the scripted payments-latency investigation and returns its diagnosis and ledger.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function investigation() {
  const tools = await replayTools(join(ROOT, "chaos", "fixtures", "payments-latency"), defaultRunbooks(ROOT));
  return investigate(new FakeProvider(join(ROOT, "agents", "responses", "payments-latency.json")), {}, tools,
    { maxSteps: 8, maxTokens: 50000, maxToolCallsPerStep: 5 });
}

test("a catalog action on an allowed target, citing real evidence, is accepted with its verification and rollback", async () => {
  const { diagnosis, ledger } = await investigation();
  const result = plan(diagnosis!, [FAILOVER], ledger, CATALOG);
  assert.equal(result.refused.length, 0, JSON.stringify(result.refused));
  assert.equal(result.accepted[0]?.rollback, "fail_back");
  assert.match(result.accepted[0]!.verificationQuery, /checkout/);
});

test("actions outside the catalog, disallowed targets, invented evidence and empty effects are refused", async () => {
  const { diagnosis, ledger } = await investigation();
  const result = plan(diagnosis!, [
    { ...FAILOVER, action: "delete_database" },
    { ...FAILOVER, target: "checkout" },
    { ...FAILOVER, evidence: ["ev-99"] },
    { ...FAILOVER, expectedEffect: "" },
  ], ledger, CATALOG);
  assert.equal(result.accepted.length, 0);
  assert.deepEqual(result.refused.map((r) => r.reason), ["delete_database is not in the catalog", "failover cannot target checkout",
    "a proposal must cite evidence that exists in the ledger", "a proposal must say what it should achieve"]);
});

test("a decoy's diagnosis admits no proposal, however reasonable it looks", async () => {
  const { ledger } = await investigation();
  const none: Diagnosis = { hypotheses: [{ rank: 1, component: "none", mechanism: "transient_recovered",
    statement: "It recovered on its own before anyone acted.", confidence: 0.8, evidence: ["ev-1"] }], unknowns: [] };
  const result = plan(none, [{ ...FAILOVER }], ledger, CATALOG);
  assert.equal(result.accepted.length, 0);
  assert.match(result.refused[0]!.reason, /nothing to fix/);
});

test("accepted proposals reach mcp-actions and come back as proposal IDs", async () => {
  const { diagnosis, ledger } = await investigation();
  const file = join(mkdtempSync(join(tmpdir(), "proposals-")), "proposals.jsonl");
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await createActionsServer(file).connect(serverSide);
  const client = new Client({ name: "planner", version: "1" });
  await client.connect(clientSide);
  /**
   * Calls propose_action through MCP.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const propose: Tool = async (args) => {
    const r = await client.callTool({ name: "propose_action", arguments: args });
    return { text: (r.content as { text: string }[])[0]!.text, isError: r.isError === true };
  };
  const ids = await submit(plan(diagnosis!, [FAILOVER], ledger, CATALOG).accepted, propose);
  assert.equal(ids.length, 1);
  assert.equal(JSON.parse(readFileSync(file, "utf8").trim()).id, ids[0]);
});
