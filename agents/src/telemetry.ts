/**
 * GuardiAI's own telemetry for the agents. One investigation becomes an OTLP trace, an `invoke_agent` span
 * with one `execute_tool` span per ledger entry, and OTLP metrics, following OpenTelemetry's GenAI semantic
 * conventions (`gen_ai.*`) plus GuardiAI's own (`guardia.*`). It speaks OTLP/HTTP JSON with fetch, so it needs
 * no SDK, and it sends only when an endpoint is given: a collector that is down never fails an investigation.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { randomBytes } from "node:crypto";
import type { Investigation } from "./investigator.ts";

type Value = { stringValue: string } | { intValue: string } | { boolValue: boolean } | { doubleValue: number };

/**
 * Turns a record into OTLP attributes.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function attributes(values: Record<string, string | number | boolean>): { key: string; value: Value }[] {
  return Object.entries(values).map(([key, v]) => ({ key, value: typeof v === "boolean" ? { boolValue: v }
    : typeof v === "number" ? (Number.isInteger(v) ? { intValue: String(v) } : { doubleValue: v }) : { stringValue: v } }));
}

/**
 * A single-observation histogram data point, with GenAI's suggested bucket bounds.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function histogramPoint(value: number, bounds: number[], attrs: Record<string, string | number | boolean>, start: string, end: string) {
  const counts = bounds.map(() => "0").concat("0");
  counts[bounds.findIndex((b) => value <= b) === -1 ? bounds.length : bounds.findIndex((b) => value <= b)] = "1";
  return { startTimeUnixNano: start, timeUnixNano: end, count: "1", sum: value, bucketCounts: counts, explicitBounds: bounds, attributes: attributes(attrs) };
}

/**
 * Sends an investigation's trace and metrics to an OTLP/HTTP endpoint; returns whether both were accepted.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function exportInvestigation(endpoint: string | undefined, scenario: string, result: Investigation, durationMs: number): Promise<boolean> {
  if (!endpoint) {
    return false;
  }
  const endNs = BigInt(Date.now()) * 1_000_000n;
  const startNs = endNs - BigInt(Math.round(durationMs)) * 1_000_000n;
  const start = String(startNs);
  const end = String(endNs);
  const traceId = randomBytes(16).toString("hex");
  const rootId = randomBytes(8).toString("hex");
  const valid = result.problems.length === 0 && result.diagnosis !== undefined;
  const provider = result.provider.split(":")[0] ?? result.provider;
  const common = { "gen_ai.provider.name": provider, "gen_ai.agent.name": "investigator", "guardia.scenario": scenario };
  const slice = (endNs - startNs) / BigInt(Math.max(1, result.ledger.length));
  const spans = [
    { traceId, spanId: rootId, name: "invoke_agent investigator", kind: 1, startTimeUnixNano: start, endTimeUnixNano: end,
      attributes: attributes({ ...common, "gen_ai.operation.name": "invoke_agent", "gen_ai.usage.input_tokens": result.tokens.inputTokens,
        "gen_ai.usage.output_tokens": result.tokens.outputTokens, "guardia.steps": result.steps, "guardia.tool_calls": result.ledger.length,
        "guardia.evidence_valid": valid, "guardia.stopped_by": result.stoppedBy ?? "diagnosis" }),
      status: { code: valid ? 1 : 2 } },
    ...result.ledger.map((entry, i) => ({ traceId, spanId: randomBytes(8).toString("hex"), parentSpanId: rootId, name: `execute_tool ${entry.tool}`,
      kind: 1, startTimeUnixNano: String(startNs + slice * BigInt(i)), endTimeUnixNano: String(startNs + slice * BigInt(i + 1)),
      attributes: attributes({ ...common, "gen_ai.operation.name": "execute_tool", "gen_ai.tool.name": entry.tool, "gen_ai.tool.call.id": entry.id,
        "guardia.step": entry.step }), status: { code: entry.isError ? 2 : 1 } })),
  ];
  const tokenBounds = [1, 4, 16, 64, 256, 1024, 4096, 16384, 65536, 262144, 1048576, 4194304, 16777216, 67108864];
  const secondBounds = [0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28, 2.56, 5.12, 10.24, 20.48, 40.96, 81.92];
  const gen = { "gen_ai.operation.name": "invoke_agent", "gen_ai.provider.name": provider };
  const metrics = [
    { name: "gen_ai.client.token.usage", unit: "{token}", description: "Tokens used by the investigation, by type.",
      histogram: { aggregationTemporality: 2, dataPoints: [
        histogramPoint(result.tokens.inputTokens, tokenBounds, { ...gen, "gen_ai.token.type": "input" }, start, end),
        histogramPoint(result.tokens.outputTokens, tokenBounds, { ...gen, "gen_ai.token.type": "output" }, start, end)] } },
    { name: "gen_ai.client.operation.duration", unit: "s", description: "Time from alert to diagnosis.",
      histogram: { aggregationTemporality: 2, dataPoints: [histogramPoint(durationMs / 1000, secondBounds, gen, start, end)] } },
    { name: "guardia.investigations", unit: "{investigation}", description: "Investigations, by whether their evidence validated.",
      sum: { aggregationTemporality: 2, isMonotonic: true, dataPoints: [{ startTimeUnixNano: start, timeUnixNano: end, asInt: "1",
        attributes: attributes({ ...gen, "guardia.evidence_valid": valid }) }] } },
    { name: "guardia.investigation.steps", unit: "{step}", description: "Steps the investigation took.",
      histogram: { aggregationTemporality: 2, dataPoints: [histogramPoint(result.steps, [1, 2, 3, 4, 6, 8, 12], gen, start, end)] } },
  ];
  // Prometheus takes only cumulative OTLP metrics; each investigation is its own instance, so its own series.
  const resource = { attributes: attributes({ "service.name": "guardia-agents", "service.namespace": "guardia",
    "service.instance.id": randomBytes(8).toString("hex") }) };
  const scope = { name: "guardia.agents", version: "0.9.0" };
  /**
   * Posts one OTLP payload; a failure is reported, never thrown.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const post = async (path: string, body: unknown) => {
    try {
      const response = await fetch(`${endpoint}${path}`, { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body), signal: AbortSignal.timeout(5000) });
      return response.ok;
    } catch {
      return false;
    }
  };
  const traces = await post("/v1/traces", { resourceSpans: [{ resource, scopeSpans: [{ scope, spans }] }] });
  const sent = await post("/v1/metrics", { resourceMetrics: [{ resource, scopeMetrics: [{ scope, metrics }] }] });
  return traces && sent;
}
