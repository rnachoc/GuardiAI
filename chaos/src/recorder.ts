/**
 * The snapshot recorder. After a scenario runs, it captures what an on-call engineer could have seen
 * (metrics, logs, traces, change events and the alert) into a fixture bundle, and keeps what only the
 * grader may see (the fault timeline and the evidence measurements) apart, under truth/. Every file is
 * canonical JSON, gzip-compressed without timestamps, and listed with its SHA-256 in manifest.json, so a
 * bundle replays byte for byte and any edit is detected.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { gunzipSync, gzipSync } from "node:zlib";
import type { RunRecord } from "./run.ts";
import type { Evidence, Scenario } from "./scenario.ts";

export interface Sources {
  prometheusUrl: string;
  lokiUrl: string;
  tempoUrl: string;
}

/** The metric names a replayed investigation can query: traffic, latency, errors, business, lag, runtime. */
export const RECORDED_METRICS = [
  "http_server_request_duration_seconds_bucket", "http_server_request_duration_seconds_count",
  "http_server_request_duration_seconds_sum", "http_client_request_duration_seconds_bucket",
  "http_client_request_duration_seconds_count", "http_client_request_duration_seconds_sum",
  "mercadillo_checkouts_total", "mercadillo_notifications_total", "kafka_consumergroup_lag",
  "db_client_connections_usage", "db_client_connections_max", "jvm_memory_used_bytes",
  "jvm_cpu_recent_utilization_ratio", "traces_service_graph_request_total", "traces_service_graph_request_failed_total",
];

/** Labels that change on every restart or say nothing about the system; dropped to keep bundles small and stable. */
const DROPPED_LABELS = new Set(["instance", "job", "service_instance_id", "service_version", "service_namespace",
  "deployment_environment_name", "url_scheme", "network_protocol_version", "otel_scope_name", "otel_scope_version",
  "otel_scope_schema_url", "__metrics_gen_instance", "source", "client_id", "messaging_kafka_cluster_id"]);

/**
 * Shapes of secrets that must never be recorded. gitleaks cannot see inside the compressed files, so the
 * bundle check scans their decompressed text itself.
 */
const SECRET_PATTERNS: [string, RegExp][] = [
  ["an AWS access key", /\b(AKIA|ASIA)[0-9A-Z]{16}\b/],
  ["a private key", /-----BEGIN [A-Z ]*PRIVATE KEY-----/],
  ["a bearer token", /\bBearer\s+[A-Za-z0-9._~+/-]{20,}/],
  ["an Anthropic API key", /\bsk-ant-[A-Za-z0-9_-]{20,}/],
  ["a GitHub token", /\bgh[pousr]_[A-Za-z0-9]{30,}/],
  ["a password assignment", /\bpassword\s*[=:]\s*(?!local-only)[^\s"',}]{6,}/i],
];

const STEP_SECONDS = 15;
const MAX_LOG_LINES = 3000;
const MAX_TRACES = 40;
const TOOL_FILES = ["alert.json.gz", "metrics.json.gz", "logs.json.gz", "traces.json.gz", "changes.json.gz"];
const TRUTH_FILES = ["truth/run.json.gz", "truth/evidence.json.gz"];

export interface Window {
  start: number;
  firstFault: number;
  faultCheck: number;
  end: number;
}

/**
 * Works out the instants a recording is anchored to, in Unix seconds: the run's start and end, the first
 * fault, and the mid-fault instant where `fault` assertions are checked (100 s after the last fault starts,
 * but before the first fault is reverted).
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function windowOf(scenario: Scenario, run: RunRecord): Window {
  const start = Math.floor(Date.parse(run.startedAt) / 1000);
  const lastApply = Math.max(...scenario.steps.map((step) => step.at));
  const firstRevert = Math.min(...scenario.steps.map((step) => step.revertAt ?? scenario.durationSeconds));
  const firstFault = start + Math.min(...scenario.steps.map((step) => step.at));
  return { start, firstFault, faultCheck: start + Math.min(lastApply + 100, firstRevert - 3), end: start + scenario.durationSeconds };
}

/**
 * Serialises a value as canonical JSON: object keys sorted, numbers kept to 6 significant digits, so the
 * same content always produces the same bytes.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function canonical(value: unknown): string {
  return JSON.stringify(value, (_key, item: unknown) => {
    if (typeof item === "number" && Number.isFinite(item) && !Number.isInteger(item)) {
      return Number(item.toPrecision(6));
    }
    if (item && typeof item === "object" && !Array.isArray(item)) {
      return Object.fromEntries(Object.entries(item as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
    }
    return item;
  });
}

/**
 * Fetches JSON from a URL with query parameters, failing on any status other than 200.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function getJson(base: string, path: string, params: Record<string, string | number>): Promise<any> {
  const url = `${base}${path}?${new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)]))}`;
  const response = await fetch(url, { signal: AbortSignal.timeout(30000) });
  if (response.status !== 200) {
    throw new Error(`GET ${url} answered ${response.status}: ${(await response.text()).slice(0, 200)}`);
  }
  return response.json();
}

/**
 * Captures the raw series of the recorded metrics over the window, with volatile labels dropped and
 * series sorted by their labels.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function captureMetrics(sources: Sources, window: Window) {
  const body = await getJson(sources.prometheusUrl, "/api/v1/query_range", {
    query: `{__name__=~"${RECORDED_METRICS.join("|")}"}`, start: window.start - 60, end: window.end + 30, step: STEP_SECONDS,
  });
  const series = (body.data.result as { metric: Record<string, string>; values: [number, string][] }[]).map((entry) => ({
    labels: Object.fromEntries(Object.entries(entry.metric).filter(([name]) => !DROPPED_LABELS.has(name))),
    samples: entry.values.map(([t, v]) => [t, Number(v)] as [number, number]),
  }));
  const merged = new Map<string, { labels: Record<string, string>; samples: [number, number][] }>();
  for (const item of series) {
    const key = canonical(item.labels);
    const existing = merged.get(key);
    if (existing) {
      // Two series that differed only by a dropped label, such as a restarted process: add them up per instant.
      const byTime = new Map(existing.samples);
      for (const [t, v] of item.samples) {
        byTime.set(t, (byTime.get(t) ?? 0) + v);
      }
      existing.samples = [...byTime.entries()].sort((a, b) => a[0] - b[0]);
    } else {
      merged.set(key, item);
    }
  }
  return { stepSeconds: STEP_SECONDS, series: [...merged.entries()].sort(([a], [b]) => (a < b ? -1 : 1)).map(([, item]) => item) };
}

/**
 * Captures Mercadillo's log lines over the window: every warning and error, and up to 150 other lines per
 * service, oldest first.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function captureLogs(sources: Sources, window: Window) {
  const range = { start: `${window.start - 60}000000000`, end: `${window.end + 30}000000000`, direction: "forward" };
  const queries = [
    { query: '{service_namespace="mercadillo"} | detected_level=~"warn|error"', limit: MAX_LOG_LINES },
    ...["gateway", "catalog", "cart", "checkout", "payments", "inventory", "notifier"].map((service) => ({
      query: `{service_name="${service}"} | detected_level!~"warn|error"`, limit: 150 })),
  ];
  const lines = new Map<string, { time: string; service: string; level: string; traceId: string; line: string }>();
  for (const { query, limit } of queries) {
    const body = await getJson(sources.lokiUrl, "/loki/api/v1/query_range", { ...range, query, limit });
    for (const stream of body.data.result as { stream: Record<string, string>; values: [string, string][] }[]) {
      for (const [time, line] of stream.values) {
        const entry = { time, service: stream.stream.service_name ?? "", level: (stream.stream.detected_level ?? "").toLowerCase(),
          traceId: stream.stream.trace_id ?? "", line };
        lines.set(`${time}|${entry.service}|${line}`, entry);
      }
    }
  }
  return { lines: [...lines.values()].sort((a, b) => (a.time === b.time ? (a.line < b.line ? -1 : 1) : a.time < b.time ? -1 : 1)) };
}

/**
 * Keeps the parts of a span an investigation uses, and drops the rest.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function trimSpan(service: string, span: any) {
  const kept = ["http.route", "http.request.method", "http.response.status_code", "server.address", "db.system",
    "db.operation", "db.sql.table", "messaging.destination.name", "error.type", "exception.message"];
  const attributes: Record<string, string> = {};
  for (const attribute of span.attributes ?? []) {
    if (kept.includes(attribute.key)) {
      const value = attribute.value ?? {};
      attributes[attribute.key] = String(value.stringValue ?? value.intValue ?? value.doubleValue ?? value.boolValue ?? "");
    }
  }
  for (const event of span.events ?? []) {
    for (const attribute of event.attributes ?? []) {
      if (attribute.key === "exception.message") {
        attributes["exception.message"] = String(attribute.value?.stringValue ?? "").slice(0, 300);
      }
    }
  }
  const start = BigInt(span.startTimeUnixNano);
  return {
    spanId: span.spanId, parentSpanId: span.parentSpanId ?? "", service, name: span.name, kind: span.kind ?? "",
    startUnixNano: span.startTimeUnixNano, durationMs: Number((BigInt(span.endTimeUnixNano) - start) / 1000n) / 1000,
    status: span.status?.code ?? "STATUS_CODE_UNSET", attributes,
  };
}

/**
 * Captures up to 40 traces from the window: failed ones first, then slow ones, then a sample of normal ones.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function captureTraces(sources: Sources, window: Window) {
  const range = { start: window.start - 60, end: window.end + 30 };
  const picked: string[] = [];
  for (const [q, limit] of [["{ status = error }", 15], ["{ duration > 1s }", 15], ['{ resource.service.name = "gateway" }', 20]] as const) {
    const body = await getJson(sources.tempoUrl, "/api/search", { ...range, q, limit });
    for (const trace of body.traces ?? []) {
      if (!picked.includes(trace.traceID) && picked.length < MAX_TRACES) {
        picked.push(trace.traceID);
      }
    }
  }
  const traces = [];
  for (const traceId of picked.sort()) {
    const body = await getJson(sources.tempoUrl, `/api/v2/traces/${traceId}`, {});
    const spans = [];
    for (const resourceSpans of (body.trace ?? body).resourceSpans ?? []) {
      const service = (resourceSpans.resource.attributes ?? []).find((a: any) => a.key === "service.name")?.value?.stringValue ?? "";
      for (const scope of resourceSpans.scopeSpans ?? []) {
        for (const span of scope.spans ?? []) {
          spans.push(trimSpan(service, span));
        }
      }
    }
    spans.sort((a, b) => (a.startUnixNano === b.startUnixNano ? (a.spanId < b.spanId ? -1 : 1) : BigInt(a.startUnixNano) < BigInt(b.startUnixNano) ? -1 : 1));
    traces.push({ traceId, spans });
  }
  return { traces };
}

/**
 * Measures one piece of evidence at one instant: a metric's value, or the number of log lines or traces
 * that match from the first fault to that instant. An empty metric result is recorded as null.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function measure(sources: Sources, evidence: Evidence, from: number, at: number): Promise<number | null> {
  if (evidence.kind === "metric") {
    const body = await getJson(sources.prometheusUrl, "/api/v1/query", { query: evidence.query, time: at });
    const result = body.data.result as { value: [number, string] }[];
    return result.length && result[0] ? Number(result[0].value[1]) : null;
  }
  if (evidence.kind === "log") {
    const body = await getJson(sources.lokiUrl, "/loki/api/v1/query_range", {
      query: evidence.query, start: `${from}000000000`, end: `${at}000000000`, limit: 1000 });
    return (body.data.result as { values: unknown[] }[]).reduce((total, stream) => total + stream.values.length, 0);
  }
  if (evidence.kind === "trace") {
    const body = await getJson(sources.tempoUrl, "/api/search", { q: evidence.query, start: from, end: at, limit: 100 });
    return (body.traces ?? []).length;
  }
  return null;
}

/**
 * Checks one assertion against a measured value. A missing metric counts as zero, since a series that
 * stops reporting means nothing is being served.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function holds(assertion: Evidence["assert"], measured: number | null): boolean {
  const value = measured ?? 0;
  switch (assertion.op) {
    case ">": return value > assertion.value;
    case ">=": return value >= assertion.value;
    case "<": return value < assertion.value;
    case "<=": return value <= assertion.value;
    case "==": return value === assertion.value;
  }
}

/**
 * Captures a whole bundle for a scenario that has just run.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function capture(sources: Sources, scenario: Scenario, run: RunRecord): Promise<Record<string, unknown>> {
  const window = windowOf(scenario, run);
  const evidence = [];
  for (const item of scenario.groundTruth.evidence) {
    const at = item.assert.when === "fault" ? window.faultCheck : window.end;
    const measured = await measure(sources, item, window.firstFault, at);
    evidence.push({ id: item.id, kind: item.kind, query: item.query, assert: item.assert, at, measured, holds: holds(item.assert, measured) });
  }
  return {
    "alert.json.gz": { name: scenario.alert.name, severity: scenario.alert.severity, service: scenario.alert.service,
      annotations: scenario.alert.annotations, firedAt: window.start + scenario.alert.firesAfterSeconds },
    "metrics.json.gz": await captureMetrics(sources, window),
    "logs.json.gz": await captureLogs(sources, window),
    "traces.json.gz": await captureTraces(sources, window),
    // No scenario in this set changes a deploy, a config or a flag, so there are no change events to record.
    "changes.json.gz": { events: [] },
    "truth/run.json.gz": { ...run, window },
    "truth/evidence.json.gz": { scenario: scenario.id, evidence },
  };
}

/**
 * Hashes bytes with SHA-256.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function sha256(bytes: Buffer): string {
  return createHash("sha256").update(bytes).digest("hex");
}

/**
 * Writes a bundle to a folder, replacing what was there, with a manifest of every file's hash.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function writeBundle(folder: string, scenario: Scenario, files: Record<string, unknown>): void {
  rmSync(folder, { recursive: true, force: true });
  mkdirSync(join(folder, "truth"), { recursive: true });
  const hashes: Record<string, string> = {};
  for (const [name, content] of Object.entries(files)) {
    const bytes = gzipSync(Buffer.from(canonical(content)), { level: 9 });
    writeFileSync(join(folder, name), bytes);
    hashes[name] = sha256(bytes);
  }
  writeFileSync(join(folder, "manifest.json"), `${JSON.stringify({
    scenario: scenario.id, format: 1, toolFiles: TOOL_FILES, truthFiles: TRUTH_FILES, sha256: hashes,
  }, null, 2)}\n`);
}

/**
 * Reads one file of a bundle, after checking its hash against the manifest.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function readBundleFile(folder: string, name: string): any {
  const manifest = JSON.parse(readFileSync(join(folder, "manifest.json"), "utf8"));
  const bytes = readFileSync(join(folder, name));
  if (manifest.sha256[name] !== sha256(bytes)) {
    throw new Error(`${folder}/${name} does not match its hash in the manifest`);
  }
  return JSON.parse(gunzipSync(bytes).toString("utf8"));
}

/**
 * Checks a bundle: every expected file exists and matches its hash, the scenario's ground truth holds in
 * the recording, the tool-facing files carry nothing from truth/, and re-serialising each file gives back
 * the same bytes. Returns the problems found, or an empty list.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function verifyBundle(folder: string, scenario: Scenario): string[] {
  const problems: string[] = [];
  if (!existsSync(join(folder, "manifest.json"))) {
    return [`${folder}: no manifest.json`];
  }
  const manifest = JSON.parse(readFileSync(join(folder, "manifest.json"), "utf8"));
  if (manifest.scenario !== scenario.id) {
    problems.push(`${folder}: the manifest names ${manifest.scenario}`);
  }
  for (const name of [...TOOL_FILES, ...TRUTH_FILES]) {
    try {
      const content = readBundleFile(folder, name);
      if (canonical(content) !== gunzipSync(readFileSync(join(folder, name))).toString("utf8")) {
        problems.push(`${folder}/${name} is not canonical, so it would not replay byte for byte`);
      }
    } catch (error) {
      problems.push(String((error as Error).message));
    }
  }
  if (problems.length > 0) {
    return problems;
  }
  const recorded = readBundleFile(folder, "truth/evidence.json.gz").evidence as { id: string; measured: number | null }[];
  for (const item of scenario.groundTruth.evidence) {
    const found = recorded.find((entry) => entry.id === item.id);
    if (!found) {
      problems.push(`${scenario.id}: evidence ${item.id} was not recorded`);
    } else if (!holds(item.assert, found.measured)) {
      problems.push(`${scenario.id}: evidence ${item.id} measured ${found.measured}, which fails ${item.assert.op} ${item.assert.value}`);
    }
  }
  const toolText = TOOL_FILES.map((name) => canonical(readBundleFile(folder, name))).join("\n");
  for (const leak of ["guardia-step-", "toxiproxy", scenario.groundTruth.rootCause.mechanism]) {
    if (toolText.includes(leak)) {
      problems.push(`${scenario.id}: tool-facing files mention '${leak}', which belongs to the ground truth`);
    }
  }
  for (const [what, pattern] of SECRET_PATTERNS) {
    if (pattern.test(toolText)) {
      problems.push(`${scenario.id}: tool-facing files contain what looks like ${what}`);
    }
  }
  return problems;
}
