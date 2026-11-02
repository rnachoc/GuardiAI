/**
 * mcp-traces: read-only traces for GuardiAI's agents. Search takes structured parameters instead of raw
 * TraceQL, so nothing an agent writes is spliced into a query. A trace is returned span by span, and its
 * critical path is summarised. Free-text span attributes are redacted and wrapped as untrusted data.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { readToolFile, replayClock, type Span } from "../shared/replay.ts";
import { answer, modeFromEnvironment, READ_ONLY, refuse, type Mode } from "../shared/server.ts";
import { untrusted } from "../shared/untrusted.ts";

export const MAX_RESULTS = 20;
export const MAX_SPANS = 300;
const SERVICES = ["gateway", "catalog", "cart", "checkout", "payments", "inventory", "notifier"] as const;
const FREE_TEXT = new Set(["exception.message", "error.type"]);

export interface Trace {
  traceId: string;
  spans: Span[];
}

/**
 * Summarises a trace: its root, total duration, services and errors.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function summarise(trace: Trace) {
  const root = trace.spans.find((span) => !span.parentSpanId) ?? trace.spans[0];
  return {
    traceId: trace.traceId,
    root: root ? `${root.service} ${root.name}` : "",
    durationMs: root?.durationMs ?? 0,
    spans: trace.spans.length,
    errors: trace.spans.filter((span) => span.status === "STATUS_CODE_ERROR").length,
    services: [...new Set(trace.spans.map((span) => span.service))].sort(),
  };
}

/**
 * Follows, from the root, the child that ends last at each level: the chain of calls that decided how
 * long the request took. Each step reports its own duration and the time not spent in that child.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function criticalPath(trace: Trace) {
  const children = new Map<string, Span[]>();
  for (const span of trace.spans) {
    children.set(span.parentSpanId, [...(children.get(span.parentSpanId) ?? []), span]);
  }
  /**
   * Returns when a span ended, in milliseconds since the epoch.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const end = (span: Span) => Number(BigInt(span.startUnixNano) / 1000n) / 1000 + span.durationMs;
  const path = [];
  let current = trace.spans.find((span) => !span.parentSpanId);
  while (current && path.length < 50) {
    const kids = children.get(current.spanId) ?? [];
    const next = kids.reduce<Span | undefined>((latest, kid) => (!latest || end(kid) > end(latest) ? kid : latest), undefined);
    path.push({ service: current.service, name: current.name, durationMs: current.durationMs,
      selfMs: Number((current.durationMs - (next?.durationMs ?? 0)).toFixed(3)), status: current.status });
    current = next;
  }
  return path;
}

/**
 * Prepares a span for the agent: free-text attributes redacted and wrapped as untrusted.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function present(span: Span) {
  const attributes: Record<string, string> = {};
  const flags: string[] = [];
  for (const [name, value] of Object.entries(span.attributes)) {
    if (FREE_TEXT.has(name)) {
      const safe = untrusted(value, 500);
      attributes[name] = safe.text;
      flags.push(...safe.flags);
    } else {
      attributes[name] = value.slice(0, 200);
    }
  }
  return { ...span, attributes, ...(flags.length ? { injectionFlags: [...new Set(flags)] } : {}) };
}

/**
 * Builds the server for one mode. Exported so tests can connect a client to it in memory.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function createTracesServer(mode: Mode, now: () => number = () => Math.floor(Date.now() / 1000)): McpServer {
  const server = new McpServer({ name: "mcp-traces", version: "0.3.0" });
  const recorded: Trace[] = mode.kind === "replay" ? readToolFile(mode.bundle, "traces.json.gz").traces : [];
  const clock = mode.kind === "replay" ? () => replayClock(mode.bundle) + (mode.elapsed?.() ?? 0) : now;

  /**
   * Fetches one trace from Tempo and trims it to the recorder's span shape.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  async function liveTrace(baseUrl: string, traceId: string): Promise<Trace | undefined> {
    const response = await fetch(`${baseUrl}/api/v2/traces/${traceId}`, { signal: AbortSignal.timeout(10000) });
    if (response.status !== 200) {
      return undefined;
    }
    const body = await response.json() as any;
    const spans: Span[] = [];
    for (const resourceSpans of (body.trace ?? body).resourceSpans ?? []) {
      const service = (resourceSpans.resource.attributes ?? []).find((a: any) => a.key === "service.name")?.value?.stringValue ?? "";
      for (const scope of resourceSpans.scopeSpans ?? []) {
        for (const span of scope.spans ?? []) {
          const attributes: Record<string, string> = {};
          for (const a of span.attributes ?? []) {
            attributes[a.key] = String(a.value?.stringValue ?? a.value?.intValue ?? a.value?.doubleValue ?? a.value?.boolValue ?? "");
          }
          spans.push({ spanId: span.spanId, parentSpanId: span.parentSpanId ?? "", service, name: span.name, kind: span.kind ?? "",
            startUnixNano: span.startTimeUnixNano, durationMs: Number(BigInt(span.endTimeUnixNano) - BigInt(span.startTimeUnixNano)) / 1e6,
            status: span.status?.code ?? "STATUS_CODE_UNSET", attributes });
        }
      }
    }
    return { traceId, spans };
  }

  server.registerTool("search_traces", {
    title: "Search traces",
    description: "Finds recent traces by service, minimum duration, errors only, or HTTP route. At most 20 results.",
    inputSchema: {
      service: z.enum(SERVICES).optional(),
      minDurationMs: z.number().int().min(0).max(600000).optional(),
      errorsOnly: z.boolean().default(false),
      route: z.string().regex(/^\/[A-Za-z0-9/{}_.-]{0,100}$/).optional(),
      sinceSeconds: z.number().int().min(60).max(3600).default(900),
      limit: z.number().int().min(1).max(MAX_RESULTS).default(10),
    },
    annotations: READ_ONLY,
  }, async ({ service, minDurationMs, errorsOnly, route, sinceSeconds, limit }) => {
    const end = clock();
    const start = end - sinceSeconds;
    /**
     * Tells whether a trace meets every given condition.
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    const wanted = (trace: Trace) => {
      const startSeconds = Math.min(...trace.spans.map((span) => Number(BigInt(span.startUnixNano) / 1000000000n)));
      return startSeconds >= start && startSeconds <= end
        && (!service || trace.spans.some((span) => span.service === service))
        && (minDurationMs === undefined || summarise(trace).durationMs >= minDurationMs)
        && (!errorsOnly || trace.spans.some((span) => span.status === "STATUS_CODE_ERROR"))
        && (!route || trace.spans.some((span) => span.attributes["http.route"] === route));
    };
    if (mode.kind === "replay") {
      const found = recorded.filter(wanted);
      return answer({ traces: found.slice(0, limit).map(summarise), truncated: found.length > limit });
    }
    // Built from validated parameters only; nothing the agent types reaches TraceQL unchecked.
    const conditions = [service && `resource.service.name = "${service}"`, minDurationMs !== undefined && `duration > ${minDurationMs}ms`,
      errorsOnly && "status = error", route && `span.http.route = "${route}"`].filter(Boolean);
    try {
      const params = new URLSearchParams({ q: `{ ${conditions.join(" && ") || "true"} }`, start: String(start), end: String(end), limit: String(limit) });
      const response = await fetch(`${mode.baseUrl}/api/search?${params}`, { signal: AbortSignal.timeout(10000) });
      const body = await response.json() as any;
      const traces = [];
      for (const hit of (body.traces ?? []).slice(0, limit)) {
        const trace = await liveTrace(mode.baseUrl, hit.traceID);
        if (trace) {
          traces.push(summarise(trace));
        }
      }
      return answer({ traces, truncated: (body.traces ?? []).length > limit });
    } catch {
      return refuse("the traces could not be searched");
    }
  });

  server.registerTool("get_trace", {
    title: "Get a trace",
    description: "Returns a trace's spans, at most 300, with free-text attributes marked as untrusted data.",
    inputSchema: { traceId: z.string().regex(/^[0-9a-f]{16,32}$/) },
    annotations: READ_ONLY,
  }, async ({ traceId }) => {
    const trace = mode.kind === "replay" ? recorded.find((t) => t.traceId === traceId) : await liveTrace(mode.baseUrl, traceId).catch(() => undefined);
    if (!trace) {
      return refuse(`trace ${traceId} was not found`);
    }
    return answer({ summary: summarise(trace), spans: trace.spans.slice(0, MAX_SPANS).map(present), truncated: trace.spans.length > MAX_SPANS });
  });

  server.registerTool("critical_path", {
    title: "Critical path of a trace",
    description: "Follows the chain of calls that decided how long a request took, with each step's own time.",
    inputSchema: { traceId: z.string().regex(/^[0-9a-f]{16,32}$/) },
    annotations: READ_ONLY,
  }, async ({ traceId }) => {
    const trace = mode.kind === "replay" ? recorded.find((t) => t.traceId === traceId) : await liveTrace(mode.baseUrl, traceId).catch(() => undefined);
    return trace ? answer({ traceId, path: criticalPath(trace) }) : refuse(`trace ${traceId} was not found`);
  });

  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await createTracesServer(modeFromEnvironment("TEMPO_URL", "http://tempo:3200")).connect(new StdioServerTransport());
}
