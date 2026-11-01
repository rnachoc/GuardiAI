/**
 * Tests for the Anthropic provider against a local stand-in for the Messages API: the requests it sends,
 * the turns it returns, and a recording that the fake provider replays to the same diagnosis. No key is
 * used and nothing leaves the machine.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { createServer } from "node:http";
import { mkdtempSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import Anthropic from "@anthropic-ai/sdk";
import { AnthropicProvider, MODEL } from "../src/anthropic.ts";
import { investigate } from "../src/investigator.ts";
import { FakeProvider } from "../src/providers.ts";
import { defaultRunbooks, replayToolset } from "../src/tools.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const BUNDLE = join(ROOT, "chaos", "fixtures", "payments-latency");
const LIMITS = { maxSteps: 6, maxTokens: 100000, maxToolCallsPerStep: 5 };
const QUERY = 'histogram_quantile(0.95, sum by (le, server_address) (rate(http_client_request_duration_seconds_bucket{service_name="checkout"}[1m])))';

/**
 * Starts a stand-in for POST /v1/messages that answers with a tool call first and a diagnosis second,
 * and keeps every request body it received.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function standIn(): Promise<{ url: string; requests: any[]; close: () => void }> {
  const requests: any[] = [];
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      requests.push(JSON.parse(body));
      const first = requests.length === 1;
      const content = first
        ? [{ type: "tool_use", id: "toolu_1", name: "metrics__query_metrics", input: { query: QUERY } }]
        : [{ type: "tool_use", id: "toolu_2", name: "submit_diagnosis", input: {
          hypotheses: [{ rank: 1, component: "payments", mechanism: "network_latency", confidence: 0.7,
            statement: "Checkout's p95 to payments-proxy is far above everything else it calls.", evidence: ["ev-1"] }],
          unknowns: [] } }];
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ id: `msg_${requests.length}`, type: "message", role: "assistant", model: MODEL, content,
        stop_reason: "tool_use", stop_sequence: null, usage: { input_tokens: 3000, output_tokens: 200 } }));
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return { url: `http://127.0.0.1:${(server.address() as AddressInfo).port}`, requests, close: () => server.close() };
}

test("the provider drives a full investigation, sends valid requests, and its recording replays", async () => {
  const api = await standIn();
  try {
    const { tools, definitions } = await replayToolset(BUNDLE, defaultRunbooks(ROOT));
    const client = new Anthropic({ apiKey: "local-only-test", baseURL: api.url, maxRetries: 0 });
    const provider = new AnthropicProvider(definitions, client);
    const result = await investigate(provider, { name: "CheckoutLatencyHigh" }, tools, LIMITS);

    assert.deepEqual(result.problems, []);
    assert.equal(result.diagnosis?.hypotheses[0]?.component, "payments");
    assert.equal(api.requests.length, 2);
    const [first, second] = api.requests;
    assert.equal(first.model, "claude-opus-5-5");
    assert.ok(first.tools.every((t: { name: string }) => /^[a-zA-Z0-9_-]{1,64}$/.test(t.name)), "every tool name is one the API accepts");
    assert.ok(first.tools.some((t: { name: string }) => t.name === "submit_diagnosis"));
    assert.equal(first.tool_choice, undefined, "forced tool choice is not sent; Opus 5.5 refuses it");
    const answer = second.messages.at(-1).content[0];
    assert.equal(answer.type, "tool_result");
    assert.equal(answer.tool_use_id, "toolu_1");
    assert.match(answer.content, /payments-proxy/);

    const path = join(mkdtempSync(join(tmpdir(), "recording-")), "payments-latency.json");
    provider.saveRecording(path, "payments-latency");
    const replayed = await investigate(new FakeProvider(path), { name: "CheckoutLatencyHigh" }, tools, LIMITS);
    assert.deepEqual(replayed.problems, []);
    assert.deepEqual(replayed.diagnosis, result.diagnosis);
    assert.match(new FakeProvider(path).provenance, /^Recorded from claude-opus-5-5 on \d{4}-\d{2}-\d{2}/);
  } finally {
    api.close();
  }
});
