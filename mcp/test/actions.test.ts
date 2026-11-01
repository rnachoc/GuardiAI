/**
 * Tests for mcp-actions: it records proposals and returns their IDs, refuses malformed ones, caps how many
 * arrive per minute, and contains no way to execute anything.
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
import { createActionsServer, MAX_PER_MINUTE } from "../src/actions/server.ts";

const PROPOSAL = { action: "restart_service", target: "inventory", reason: "inventory stopped answering",
  evidence: ["ev-1"], expectedEffect: "checkouts succeed again" };

/**
 * Connects a client to a server writing to a new file, with a controllable clock.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function connect(clock: { now: number }) {
  const file = join(mkdtempSync(join(tmpdir(), "proposals-")), "proposals.jsonl");
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await createActionsServer(file, () => clock.now).connect(serverSide);
  const client = new Client({ name: "test", version: "1" });
  await client.connect(clientSide);
  /**
   * Proposes and returns whether it was refused and the answer's text.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const propose = async (args: Record<string, unknown>) => {
    const result = await client.callTool({ name: "propose_action", arguments: args });
    return { isError: result.isError === true, text: (result.content as { text: string }[])[0]!.text };
  };
  return { file, propose };
}

test("a proposal is recorded with an ID and awaits the policy engine", async () => {
  const { file, propose } = await connect({ now: 0 });
  const { isError, text } = await propose(PROPOSAL);
  assert.equal(isError, false);
  const { proposalId, status } = JSON.parse(text);
  assert.match(proposalId, /^prop-[0-9a-f-]{36}$/);
  assert.equal(status, "awaiting_policy");
  const stored = JSON.parse(readFileSync(file, "utf8").trim());
  assert.equal(stored.id, proposalId);
  assert.deepEqual(stored.evidence, ["ev-1"]);
});

test("an action outside the catalog is still only a proposal; judging it is the policy engine's job", async () => {
  const { file, propose } = await connect({ now: 0 });
  assert.equal((await propose({ ...PROPOSAL, action: "delete_database" })).isError, false);
  assert.equal(JSON.parse(readFileSync(file, "utf8").trim()).status, "awaiting_policy");
});

test("malformed proposals are refused: no evidence, odd names, shell text", async () => {
  const { propose } = await connect({ now: 0 });
  for (const bad of [{ ...PROPOSAL, evidence: [] }, { ...PROPOSAL, target: "inventory; rm -rf /" },
    { ...PROPOSAL, action: "Restart Service" }, { ...PROPOSAL, evidence: ["../etc"] }]) {
    assert.equal((await propose(bad)).isError, true, JSON.stringify(bad));
  }
});

test("a runaway loop is capped per minute, and the cap resets", async () => {
  const clock = { now: 0 };
  const { propose } = await connect(clock);
  for (let i = 0; i < MAX_PER_MINUTE; i += 1) {
    assert.equal((await propose(PROPOSAL)).isError, false);
  }
  assert.match((await propose(PROPOSAL)).text, /at most 10 proposals a minute/);
  clock.now = 61000;
  assert.equal((await propose(PROPOSAL)).isError, false);
});

test("the server's code has no way to execute anything", () => {
  const source = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "..", "src", "actions", "server.ts"), "utf8");
  for (const forbidden of ["child_process", "docker", "fetch(", "exec(", "spawn(", "/var/run"]) {
    assert.ok(!source.includes(forbidden), `mcp-actions must not use ${forbidden}`);
  }
});
