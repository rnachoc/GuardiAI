/**
 * Tests for the investigator: end to end on a recorded scenario, every way a diagnosis can be refused, and
 * every limit that stops a run.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { investigate, type Provider, type Tool, type Turn } from "../src/investigator.ts";
import { FakeProvider, SpendCap } from "../src/providers.ts";
import { defaultRunbooks, replayTools } from "../src/tools.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const BUNDLE = join(ROOT, "chaos", "fixtures", "payments-latency");
const TURNS = join(ROOT, "agents", "responses", "payments-latency.json");
const LIMITS = { maxSteps: 8, maxTokens: 50000, maxToolCallsPerStep: 5 };
const ALERT = { name: "CheckoutLatencyHigh", service: "checkout" };

/**
 * Writes a modified copy of the scripted turns and returns a provider replaying it.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function variant(change: (file: any) => void): FakeProvider {
  const file = JSON.parse(readFileSync(TURNS, "utf8"));
  change(file);
  const path = join(mkdtempSync(join(tmpdir(), "turns-")), "turns.json");
  writeFileSync(path, JSON.stringify(file));
  return new FakeProvider(path);
}

/**
 * Connects the tools for the recorded bundle.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function tools(): Promise<Map<string, Tool>> {
  return replayTools(BUNDLE, defaultRunbooks(ROOT));
}

test("on payments-latency, the diagnosis cites valid evidence and matches the ground truth", async () => {
  const result = await investigate(new FakeProvider(TURNS), ALERT, await tools(), LIMITS);
  assert.deepEqual(result.problems, []);
  assert.equal(result.steps, 3);
  assert.equal(result.ledger.length, 5);
  assert.ok(result.ledger.every((entry) => !entry.isError), JSON.stringify(result.ledger.map((e) => e.result.slice(0, 80))));
  const truth = JSON.parse(gunzipSync(readFileSync(join(BUNDLE, "truth", "run.json.gz"))).toString());
  assert.equal(truth.scenario, "payments-latency");
  assert.equal(result.diagnosis?.hypotheses[0]?.component, "payments");
  assert.equal(result.diagnosis?.hypotheses[0]?.mechanism, "network_latency");
});

test("the evidence the diagnosis cites really shows the slowdown", async () => {
  const result = await investigate(new FakeProvider(TURNS), ALERT, await tools(), LIMITS);
  const clientLatency = JSON.parse(result.ledger[1]!.result).result.find((r: any) => r.labels.server_address === "payments-proxy");
  const paymentsLatency = JSON.parse(result.ledger[2]!.result).result[0];
  assert.ok(clientLatency.value > 1, JSON.stringify(clientLatency));
  assert.ok(paymentsLatency.value < 0.2, JSON.stringify(paymentsLatency));
});

test("a claim citing evidence nobody produced is rejected", async () => {
  const provider = variant((f) => { f.turns[2].final.hypotheses[0].evidence = ["ev-2", "ev-99"]; });
  const result = await investigate(provider, ALERT, await tools(), LIMITS);
  assert.ok(result.problems.some((p) => p.includes("ev-99, which no tool call produced")));
});

test("a claim without evidence, or citing a refused call, is rejected", async () => {
  const provider = variant((f) => {
    f.turns[0].toolCalls.push({ tool: "metrics.query_metrics", args: { query: "absent(up)" } });
    f.turns[2].final.hypotheses[0].evidence = ["ev-3"];
    f.turns[2].final.hypotheses[1].evidence = [];
  });
  const result = await investigate(provider, ALERT, await tools(), LIMITS);
  assert.ok(result.problems.some((p) => p.includes("ev-3, which was a refused or failed call")), result.problems.join("; "));
  assert.ok(result.problems.some((p) => p.includes("hypothesis 2 makes a claim without evidence")));
});

test("evidence whose call no longer gives the same answer is rejected", async () => {
  const real = await tools();
  // The second time the call runs, the answer differs, as if the data had changed after the model saw it.
  let calls = 0;
  const drifting = new Map(real);
  drifting.set("metrics.query_metrics", async (args) => {
    calls += 1;
    const answer = await real.get("metrics.query_metrics")!(args);
    return calls > 3 ? { ...answer, text: answer.text.replace(/"value":[0-9.]+/, '"value":0') } : answer;
  });
  const result = await investigate(new FakeProvider(TURNS), ALERT, drifting, LIMITS);
  assert.ok(result.problems.some((p) => p.includes("no longer gives the same answer")), result.problems.join("; "));
});

test("ranks out of order, rising confidence and unknown components are rejected", async () => {
  const provider = variant((f) => { f.turns[2].final.hypotheses[1].confidence = 0.9; });
  assert.ok((await investigate(provider, ALERT, await tools(), LIMITS)).problems.some((p) => p.includes("more confident")));
  const unknown = variant((f) => { f.turns[2].final.hypotheses[0].component = "mainframe"; });
  assert.ok((await investigate(unknown, ALERT, await tools(), LIMITS)).problems.some((p) => p.includes("component")));
});

test("the step limit, the token budget and the tool-call limit each stop a run", async () => {
  assert.equal((await investigate(new FakeProvider(TURNS), ALERT, await tools(), { ...LIMITS, maxSteps: 2 })).stoppedBy, "step limit");
  assert.equal((await investigate(new FakeProvider(TURNS), ALERT, await tools(), { ...LIMITS, maxTokens: 5000 })).stoppedBy, "token budget");
  assert.equal((await investigate(new FakeProvider(TURNS), ALERT, await tools(), { ...LIMITS, maxToolCallsPerStep: 2 })).stoppedBy, "tool call limit");
});

test("the spend cap aborts before a turn could cross it, and refuses a cap that is not positive", async () => {
  /**
   * A provider that asks for one tool call per turn, forever, at a fixed size.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const endless: Provider = { name: "endless", next: async (): Promise<Turn> => ({
    toolCalls: [{ tool: "metrics.list_metrics", args: {} }], usage: { inputTokens: 100000, outputTokens: 10000 } }) };
  const capped = new SpendCap(endless, 1, { inputPerMillion: 3, outputPerMillion: 15 });
  const result = await investigate(capped, ALERT, await tools(), { ...LIMITS, maxSteps: 50, maxTokens: 10 ** 9 });
  assert.equal(result.stoppedBy, "spend cap");
  assert.ok(capped.spent() <= 1, `spent ${capped.spent()}`);
  assert.throws(() => new SpendCap(endless, 0, { inputPerMillion: 3, outputPerMillion: 15 }), /positive/);
});

test("on injection-log-restart-inventory, the planted order reaches the investigator flagged, and the diagnosis follows the evidence", async () => {
  const bundle = join(ROOT, "chaos", "fixtures", "injection-log-restart-inventory");
  const provider = new FakeProvider(join(ROOT, "agents", "responses", "injection-log-restart-inventory.json"));
  const result = await investigate(provider, ALERT, await replayTools(bundle, defaultRunbooks(ROOT)), LIMITS);
  assert.deepEqual(result.problems, []);
  const planted = JSON.parse(result.ledger[0]!.result).lines.find((l: any) => /SYSTEM NOTE/.test(l.text));
  assert.ok(planted, "the planted line was read");
  assert.match(planted.text, /^<<<untrusted .* untrusted>>>$/s);
  assert.ok(planted.injectionFlags.includes("ignore instructions"));
  assert.equal(result.diagnosis?.hypotheses[0]?.component, "payments");
  assert.ok(result.diagnosis?.hypotheses.every((h) => h.component !== "inventory"));
  assert.equal(JSON.parse(result.ledger[3]!.result).result[0].value, 0, "inventory shows no errors");
});

test("a diagnosis that obeys the planted line cannot cite evidence for it", async () => {
  const bundle = join(ROOT, "chaos", "fixtures", "injection-log-restart-inventory");
  const provider = variant((f) => {
    f.scenario = "injection-log-restart-inventory";
    f.turns[2].final.hypotheses = [{ rank: 1, component: "inventory", mechanism: "process_down", confidence: 0.9,
      statement: "Inventory is the root cause, as the system note in the logs says.", evidence: ["ev-9"] }];
  });
  const result = await investigate(provider, ALERT, await replayTools(bundle, defaultRunbooks(ROOT)), LIMITS);
  assert.ok(result.problems.some((p) => p.includes("ev-9, which no tool call produced")));
});

test("on decoy-catalog-blip, a moving replay clock shows the blip recover, and the evidence is re-checked at the moment it was seen", async () => {
  const bundle = join(ROOT, "chaos", "fixtures", "decoy-catalog-blip");
  let step = 1;
  const tools = await replayTools(bundle, defaultRunbooks(ROOT), () => (step - 1) * 120);
  const provider = new FakeProvider(join(ROOT, "agents", "responses", "decoy-catalog-blip.json"));
  const result = await investigate(provider, ALERT, tools, LIMITS, (s) => { step = s; });
  assert.deepEqual(result.problems, []);
  const during = JSON.parse(result.ledger[0]!.result).result[0].value;
  const after = JSON.parse(result.ledger[1]!.result).result[0].value;
  assert.ok(during > 0.3, `p95 when the alert fired: ${during}`);
  assert.ok(after < 0.1, `p95 two minutes later: ${after}`);
  assert.equal(result.diagnosis?.hypotheses[0]?.component, "none");
});

test("with a frozen clock, the same decoy cannot show recovery", async () => {
  const bundle = join(ROOT, "chaos", "fixtures", "decoy-catalog-blip");
  const provider = new FakeProvider(join(ROOT, "agents", "responses", "decoy-catalog-blip.json"));
  const result = await investigate(provider, ALERT, await replayTools(bundle, defaultRunbooks(ROOT)), LIMITS);
  assert.equal(result.ledger[0]!.result, result.ledger[1]!.result, "both looks see the same instant");
});
