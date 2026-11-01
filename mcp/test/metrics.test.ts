/**
 * Tests for mcp-metrics: what the PromQL subset accepts and refuses, how close replay comes to what the
 * real Prometheus measured, and the server end to end over MCP.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { evaluate, parse, quantile, QueryRejected } from "../src/metrics/promql.ts";
import { createMetricsServer } from "../src/metrics/server.ts";
import { readToolFile } from "../src/shared/replay.ts";

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "chaos", "fixtures");
const BUNDLES = readdirSync(FIXTURES).sort();

/**
 * Reads a bundle's sealed evidence directly. Only tests may do this; the servers cannot.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function truth(bundle: string): { id: string; kind: string; query: string; at: number; measured: number | null; assert: any }[] {
  return JSON.parse(gunzipSync(readFileSync(join(FIXTURES, bundle, "truth", "evidence.json.gz"))).toString()).evidence;
}

/**
 * Connects an MCP client to a metrics server replaying one bundle.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function client(bundle: string): Promise<Client> {
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await createMetricsServer({ kind: "replay", bundle: join(FIXTURES, bundle) }).connect(serverSide);
  const c = new Client({ name: "test", version: "1" });
  await c.connect(clientSide);
  return c;
}

test("every metric evidence query of every scenario parses", () => {
  for (const bundle of BUNDLES) {
    for (const item of truth(bundle).filter((e) => e.kind === "metric")) {
      assert.doesNotThrow(() => parse(item.query), `${bundle}: ${item.query}`);
    }
  }
});

test("functions outside the subset are refused, by name", () => {
  for (const query of ['label_replace(up, "a", "b", "c", "d")', "count_over_time(up[5m])", "absent(up)",
    'sort_desc(sum(rate(x_total[1m])))', "quantile_over_time(0.9, x[5m])"]) {
    assert.throws(() => parse(query), QueryRejected, query);
  }
});

test("expensive or abusive queries are refused before anything runs", () => {
  const refused: [string, RegExp][] = [
    ["rate(http_server_request_duration_seconds_count[1h])", /at most 10 minutes/],
    ["http_server_request_duration_seconds_count[1m]", /only allowed inside rate/],
    ['{service_name=~".+"}', /must name one metric/],
    ['up{job=~"(a+)+b"}', /may not repeat a group/],
    [`up{job=~"${"a".repeat(150)}"}`, /at most 100 characters/],
    ["x".repeat(1001), /at most 1000 characters/],
    [Array.from({ length: 70 }, () => "up").join(" + "), /at most 60 parts/],
    ["up; drop table", /unexpected character/],
  ];
  for (const [query, reason] of refused) {
    assert.throws(() => parse(query), reason, query.slice(0, 60));
  }
});

test("histogram quantiles interpolate inside the bucket, like Prometheus", () => {
  // 100 observations: 50 under 0.1 s, 90 under 0.5 s, all under 1 s. The 95th percentile falls halfway
  // between 0.5 and 1 s.
  assert.equal(quantile(0.95, [{ le: 0.1, count: 50 }, { le: 0.5, count: 90 }, { le: 1, count: 100 },
    { le: Number.POSITIVE_INFINITY, count: 100 }]), 0.75);
});

test("replay reproduces every recorded measurement closely enough to reach the same verdict", () => {
  let compared = 0;
  for (const bundle of BUNDLES) {
    const series = readToolFile(join(FIXTURES, bundle), "metrics.json.gz").series;
    for (const item of truth(bundle).filter((e) => e.kind === "metric")) {
      const value = evaluate(parse(item.query), item.at, series);
      const replayed = typeof value === "number" ? value : (value[0]?.value ?? 0);
      const recorded = item.measured ?? 0;
      const close = Math.abs(replayed - recorded) <= Math.max(0.05, 0.25 * Math.abs(recorded));
      assert.ok(close, `${bundle}/${item.id}: replay ${replayed}, Prometheus ${recorded}`);
      compared += 1;
    }
  }
  assert.ok(compared >= 12, `only ${compared} measurements compared`);
});

test("the server lists only read-only tools and answers a query at the replay clock", async () => {
  const c = await client("payments-latency");
  const { tools } = await c.listTools();
  assert.deepEqual(tools.map((t) => t.name).sort(), ["list_metrics", "query_metrics", "query_metrics_range"]);
  assert.ok(tools.every((t) => t.annotations?.readOnlyHint === true && t.annotations?.destructiveHint === false));
  const result = await c.callTool({ name: "query_metrics", arguments: {
    query: 'histogram_quantile(0.95, sum by (le) (rate(http_client_request_duration_seconds_bucket{service_name="checkout", server_address="payments-proxy"}[1m])))' } });
  const body = JSON.parse((result.content as { text: string }[])[0]!.text);
  assert.equal(result.isError, undefined);
  assert.ok(body.result[0].value > 1, JSON.stringify(body));
});

test("a refused query comes back as a readable error, and the future is out of reach", async () => {
  const c = await client("payments-latency");
  const refused = await c.callTool({ name: "query_metrics", arguments: { query: "absent(up)" } });
  assert.equal(refused.isError, true);
  assert.match((refused.content as { text: string }[])[0]!.text, /not an allowed function/);
  const future = await c.callTool({ name: "query_metrics", arguments: { query: "vector(1)", time: 4102444800 } });
  assert.equal(future.isError, true);
  const tooLong = await c.callTool({ name: "query_metrics_range", arguments: { query: "vector(1)", start: 0, end: 86400, stepSeconds: 15 } });
  assert.equal(tooLong.isError, true);
});

test("replay refuses to open the ground truth", () => {
  assert.throws(() => readToolFile(join(FIXTURES, "payments-latency"), "truth/evidence.json.gz"), /never serves the ground truth/);
});
