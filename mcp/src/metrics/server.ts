/**
 * mcp-metrics: read-only PromQL for GuardiAI's agents. Queries are parsed against an allow-listed subset,
 * time ranges are capped and results truncated, in both modes. Live mode forwards the validated query to
 * Prometheus; replay mode evaluates it over a recorded bundle, at the bundle's own clock.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { readToolFile, replayClock, type Series } from "../shared/replay.ts";
import { answer, modeFromEnvironment, READ_ONLY, refuse, type Mode } from "../shared/server.ts";
import { evaluate, parse, QueryRejected, type Sample } from "./promql.ts";

export const MAX_SERIES = 50;
export const MAX_RANGE_SECONDS = 2 * 3600;
export const MAX_POINTS = 240;

/**
 * Rounds values and drops non-finite ones, and keeps at most MAX_SERIES results, saying so when it cuts.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function shape(samples: Sample[]): { result: { labels: Record<string, string>; value: number | null }[]; truncated: boolean } {
  const sorted = [...samples].sort((a, b) => (JSON.stringify(a.labels) < JSON.stringify(b.labels) ? -1 : 1));
  return {
    result: sorted.slice(0, MAX_SERIES).map((s) => ({ labels: s.labels, value: Number.isFinite(s.value) ? Number(s.value.toPrecision(6)) : null })),
    truncated: sorted.length > MAX_SERIES,
  };
}

/**
 * Runs an instant query through Prometheus's HTTP API, with a timeout.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function prometheus(baseUrl: string, path: string, params: Record<string, string>): Promise<any> {
  const response = await fetch(`${baseUrl}${path}?${new URLSearchParams(params)}`, { signal: AbortSignal.timeout(10000) });
  const body = await response.json() as any;
  if (body.status !== "success") {
    throw new QueryRejected(`Prometheus refused the query: ${body.error ?? response.status}`);
  }
  return body.data;
}

/**
 * Builds the server for one mode. Exported so tests can connect a client to it in memory.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function createMetricsServer(mode: Mode, now: () => number = () => Math.floor(Date.now() / 1000)): McpServer {
  const server = new McpServer({ name: "mcp-metrics", version: "0.3.0" });
  const series: Series[] = mode.kind === "replay" ? readToolFile(mode.bundle, "metrics.json.gz").series : [];
  const clock = mode.kind === "replay" ? () => replayClock(mode.bundle) + (mode.elapsed?.() ?? 0) : now;

  server.registerTool("query_metrics", {
    title: "Instant PromQL query",
    description: "Evaluates a PromQL query at one instant (default: now). Allowed: selectors, rate, increase, "
      + "sum/avg/min/max/count by, histogram_quantile, vector, + - * /, or. Ranges up to 10m. At most 50 series.",
    inputSchema: { query: z.string().max(1000), time: z.number().int().optional() },
    annotations: READ_ONLY,
  }, async ({ query, time }) => {
    try {
      const at = time ?? clock();
      if (at > clock()) {
        return refuse("time is in the future");
      }
      const tree = parse(query);
      if (mode.kind === "replay") {
        const value = evaluate(tree, at, series);
        return answer(typeof value === "number" ? { at, scalar: value } : { at, ...shape(value) });
      }
      const data = await prometheus(mode.baseUrl, "/api/v1/query", { query, time: String(at) });
      if (data.resultType === "scalar") {
        return answer({ at, scalar: Number(data.result[1]) });
      }
      return answer({ at, ...shape(data.result.map((r: any) => ({ labels: r.metric, value: Number(r.value[1]) }))) });
    } catch (error) {
      return refuse(error instanceof QueryRejected ? error.message : "the query could not be run");
    }
  });

  server.registerTool("query_metrics_range", {
    title: "Range PromQL query",
    description: "Evaluates a PromQL query over a time range, at most 2 hours and 240 points. Same subset as query_metrics.",
    inputSchema: { query: z.string().max(1000), start: z.number().int(), end: z.number().int(), stepSeconds: z.number().int().min(15) },
    annotations: READ_ONLY,
  }, async ({ query, start, end, stepSeconds }) => {
    try {
      if (end <= start || end - start > MAX_RANGE_SECONDS) {
        return refuse(`a range must end after it starts and cover at most ${MAX_RANGE_SECONDS / 3600} hours`);
      }
      if ((end - start) / stepSeconds > MAX_POINTS) {
        return refuse(`a range may return at most ${MAX_POINTS} points per series; use a larger step`);
      }
      if (end > clock()) {
        return refuse("the range ends in the future");
      }
      const tree = parse(query);
      const bySeries = new Map<string, { labels: Record<string, string>; points: [number, number | null][] }>();
      if (mode.kind === "replay") {
        for (let t = start; t <= end; t += stepSeconds) {
          const value = evaluate(tree, t, series);
          for (const sample of typeof value === "number" ? [{ labels: {}, value }] : value) {
            const k = JSON.stringify(sample.labels);
            const entry = bySeries.get(k) ?? { labels: sample.labels, points: [] };
            entry.points.push([t, Number.isFinite(sample.value) ? Number(sample.value.toPrecision(6)) : null]);
            bySeries.set(k, entry);
          }
        }
      } else {
        const data = await prometheus(mode.baseUrl, "/api/v1/query_range",
          { query, start: String(start), end: String(end), step: String(stepSeconds) });
        for (const r of data.result) {
          bySeries.set(JSON.stringify(r.metric), { labels: r.metric, points: r.values.map(([t, v]: [number, string]) => [t, Number(v)]) });
        }
      }
      const all = [...bySeries.values()].sort((a, b) => (JSON.stringify(a.labels) < JSON.stringify(b.labels) ? -1 : 1));
      return answer({ series: all.slice(0, MAX_SERIES), truncated: all.length > MAX_SERIES });
    } catch (error) {
      return refuse(error instanceof QueryRejected ? error.message : "the query could not be run");
    }
  });

  server.registerTool("list_metrics", {
    title: "List metric names",
    description: "Lists the metric names that can be queried.",
    inputSchema: {},
    annotations: READ_ONLY,
  }, async () => {
    if (mode.kind === "replay") {
      return answer({ metrics: [...new Set(series.map((s) => s.labels.__name__ ?? ""))].filter(Boolean).sort() });
    }
    const data = await prometheus(mode.baseUrl, "/api/v1/label/__name__/values", {});
    return answer({ metrics: (data as string[]).slice(0, 500) });
  });

  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await createMetricsServer(modeFromEnvironment("PROMETHEUS_URL", "http://prometheus:9090")).connect(new StdioServerTransport());
}
