/**
 * Reads a recorded fixture bundle for replay mode. Only the tool-facing files can be opened: the ground
 * truth under truth/ is refused by name, and every file is checked against the manifest's hash first.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { gunzipSync } from "node:zlib";

const TOOL_FILES = new Set(["alert.json.gz", "metrics.json.gz", "logs.json.gz", "traces.json.gz", "changes.json.gz"]);

export interface Series {
  labels: Record<string, string>;
  samples: [number, number][];
}

export interface LogLine {
  time: string;
  service: string;
  level: string;
  traceId: string;
  line: string;
}

export interface Span {
  spanId: string;
  parentSpanId: string;
  service: string;
  name: string;
  kind: string;
  startUnixNano: string;
  durationMs: number;
  status: string;
  attributes: Record<string, string>;
}

/**
 * Opens one tool-facing file of a bundle, after checking its hash. Anything else is refused.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function readToolFile(folder: string, name: string): any {
  if (!TOOL_FILES.has(name)) {
    throw new Error(`${name} is not a tool-facing file; replay never serves the ground truth`);
  }
  const manifest = JSON.parse(readFileSync(join(folder, "manifest.json"), "utf8"));
  const bytes = readFileSync(join(folder, name));
  if (manifest.sha256?.[name] !== createHash("sha256").update(bytes).digest("hex")) {
    throw new Error(`${name} does not match its hash in the manifest`);
  }
  return JSON.parse(gunzipSync(bytes).toString("utf8"));
}

/**
 * The moment a replayed investigation starts: when the recorded alert fired, in Unix seconds.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function replayClock(folder: string): number {
  return readToolFile(folder, "alert.json.gz").firedAt as number;
}
