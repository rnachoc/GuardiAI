/**
 * Tests for the agents' telemetry: one investigation becomes an OTLP trace and OTLP metrics that follow
 * OpenTelemetry's GenAI conventions, sent to a stand-in collector.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { investigate } from "../src/investigator.ts";
import { FakeProvider } from "../src/providers.ts";
import { exportInvestigation } from "../src/telemetry.ts";
import { defaultRunbooks, replayTools } from "../src/tools.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

test("an investigation is exported as a GenAI trace and metrics", async () => {
  const received: Record<string, any> = {};
  const collector = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => { received[req.url ?? ""] = JSON.parse(body); res.writeHead(200).end("{}"); });
  });
  await new Promise<void>((resolve) => collector.listen(0, "127.0.0.1", resolve));
  const endpoint = `http://127.0.0.1:${(collector.address() as { port: number }).port}`;
  const tools = await replayTools(join(ROOT, "chaos", "fixtures", "payments-latency"), defaultRunbooks(ROOT));
  const result = await investigate(new FakeProvider(join(ROOT, "agents", "responses", "payments-latency.json")), {}, tools,
    { maxSteps: 12, maxTokens: 400000, maxToolCallsPerStep: 6 });
  await exportInvestigation(endpoint, "payments-latency", result, 1500);
  collector.close();

  const spans = received["/v1/traces"].resourceSpans[0].scopeSpans[0].spans;
  const root = spans.find((s: any) => s.name === "invoke_agent investigator");
  /**
   * Reads one attribute of a span or data point.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const attr = (span: any, key: string) => span.attributes.find((a: any) => a.key === key)?.value;
  assert.equal(attr(root, "gen_ai.operation.name").stringValue, "invoke_agent");
  assert.equal(attr(root, "gen_ai.provider.name").stringValue, "fake");
  assert.equal(attr(root, "gen_ai.usage.input_tokens").intValue, String(result.tokens.inputTokens));
  const toolSpans = spans.filter((s: any) => s.name.startsWith("execute_tool "));
  assert.equal(toolSpans.length, result.ledger.length);
  assert.ok(toolSpans.every((s: any) => s.parentSpanId === root.spanId && s.traceId === root.traceId));

  const metrics = received["/v1/metrics"].resourceMetrics[0].scopeMetrics[0].metrics;
  const names = metrics.map((m: any) => m.name);
  for (const name of ["gen_ai.client.token.usage", "gen_ai.client.operation.duration", "guardia.investigations", "guardia.investigation.steps"]) {
    assert.ok(names.includes(name), `${name} is missing`);
  }
  // Prometheus rejects delta temporality over OTLP, so every metric must be cumulative.
  assert.ok(metrics.every((m: any) => (m.histogram ?? m.sum).aggregationTemporality === 2));
  const usage = metrics.find((m: any) => m.name === "gen_ai.client.token.usage");
  assert.equal(usage.histogram.dataPoints.length, 2);
  const valid = metrics.find((m: any) => m.name === "guardia.investigations");
  assert.equal(attr(valid.sum.dataPoints[0], "guardia.evidence_valid").boolValue, true);
});

test("without an endpoint nothing is sent, and a collector that is down is not an error", async () => {
  const tools = await replayTools(join(ROOT, "chaos", "fixtures", "payments-latency"), defaultRunbooks(ROOT));
  const result = await investigate(new FakeProvider(join(ROOT, "agents", "responses", "payments-latency.json")), {}, tools,
    { maxSteps: 12, maxTokens: 400000, maxToolCallsPerStep: 6 });
  assert.equal(await exportInvestigation(undefined, "payments-latency", result, 10), false);
  assert.equal(await exportInvestigation("http://127.0.0.1:9", "payments-latency", result, 10), false);
});
