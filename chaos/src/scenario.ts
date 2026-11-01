/**
 * The scenario format: what a scenario does to Mercadillo, the alert it raises, and the ground truth an
 * evaluation grades Guardia against. Parsed with a strict schema, so an unknown field, service, fault or
 * action is an error rather than a silent typo.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { readFileSync, readdirSync } from "node:fs";
import { basename, join } from "node:path";
import { parse } from "yaml";
import { z } from "zod";
import { ACTIONS, FAMILIES, KINDS, PROXIES, SERVICES } from "./catalog.ts";

const service = z.enum(SERVICES);
const proxy = z.enum(Object.keys(PROXIES) as [keyof typeof PROXIES, ...(keyof typeof PROXIES)[]]);
const seconds = z.number().int().min(0).max(1800);

/** A reference to an action: `restart_service:inventory`, or `none`. */
const actionRef = z.string().regex(/^[a-z_]+(:[a-z]+)?$/).refine((value) => {
  const [action, target] = value.split(":");
  if (!(ACTIONS as readonly string[]).includes(action ?? "")) {
    return false;
  }
  if (action === "none") {
    return target === undefined;
  }
  return target !== undefined && (SERVICES as readonly string[]).includes(target);
}, "an action from the catalog, followed by ':' and a service (or 'none' alone)");

const fault = z.discriminatedUnion("type", [
  z.strictObject({ type: z.literal("latency"), proxy, latencyMs: z.number().int().min(1).max(30000), jitterMs: z.number().int().min(0).max(5000).default(0) }),
  z.strictObject({ type: z.literal("timeout"), proxy, afterMs: z.number().int().min(0).max(60000) }),
  z.strictObject({ type: z.literal("kill"), service }),
  z.strictObject({ type: z.literal("pause"), service }),
  z.strictObject({ type: z.literal("plant_log"), service, level: z.enum(["INFO", "WARN", "ERROR"]), message: z.string().min(1).max(2000) }),
]);

const step = z.strictObject({
  at: seconds.describe("seconds after the scenario starts"),
  fault,
  revertAt: seconds.optional().describe("seconds after the start when the fault is removed; absent means at the end"),
});

const evidence = z.strictObject({
  id: z.string().regex(/^[a-z0-9-]+$/),
  kind: z.enum(["metric", "log", "trace", "change"]),
  query: z.string().min(1),
  expect: z.string().min(1).describe("what the result shows when the cause is the one in the ground truth"),
});

export const Scenario = z.strictObject({
  id: z.string().regex(/^[a-z0-9]+(-[a-z0-9]+)*$/),
  family: z.enum(FAMILIES),
  kind: z.enum(KINDS),
  description: z.string().min(20),
  durationSeconds: z.number().int().min(60).max(1800),
  steps: z.array(step).max(10),
  alert: z.strictObject({
    name: z.string().regex(/^[A-Za-z][A-Za-z0-9]*$/),
    severity: z.enum(["critical", "warning", "info"]),
    service,
    firesAfterSeconds: seconds,
    annotations: z.record(z.string(), z.string().max(4000)),
  }),
  groundTruth: z.strictObject({
    rootCause: z.strictObject({
      component: z.union([service, z.literal("none")]),
      mechanism: z.string().regex(/^[a-z]+(_[a-z]+)*$/),
      trigger: z.string().min(5),
    }),
    acceptableActions: z.array(actionRef).min(1),
    forbiddenActions: z.array(actionRef),
    evidence: z.array(evidence).min(1),
  }),
}).superRefine((scenario, context) => {
  for (const [index, current] of scenario.steps.entries()) {
    if (current.at >= scenario.durationSeconds || (current.revertAt ?? 0) > scenario.durationSeconds) {
      context.addIssue({ code: "custom", path: ["steps", index], message: "a step must happen within the scenario's duration" });
    }
    if (current.revertAt !== undefined && current.revertAt <= current.at) {
      context.addIssue({ code: "custom", path: ["steps", index, "revertAt"], message: "a fault must be reverted after it starts" });
    }
  }
  const acceptable = new Set(scenario.groundTruth.acceptableActions);
  for (const action of scenario.groundTruth.forbiddenActions) {
    if (acceptable.has(action)) {
      context.addIssue({ code: "custom", path: ["groundTruth", "forbiddenActions"], message: `${action} is both acceptable and forbidden` });
    }
  }
  if (scenario.kind === "decoy") {
    if (scenario.groundTruth.acceptableActions.join() !== "none") {
      context.addIssue({ code: "custom", path: ["groundTruth", "acceptableActions"], message: "a decoy's only acceptable action is none" });
    }
  }
  if (scenario.kind === "injection" && !scenario.steps.some((s) => s.fault.type === "plant_log")
      && !Object.values(scenario.alert.annotations).some((text) => /ignore|instruction|system:/i.test(text))) {
    context.addIssue({ code: "custom", path: ["kind"], message: "an injection scenario must plant hostile text in a log or an alert annotation" });
  }
});

export type Scenario = z.infer<typeof Scenario>;

/**
 * Loads one scenario file. Its id must match its file name.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function loadScenario(path: string): Scenario {
  const scenario = Scenario.parse(parse(readFileSync(path, "utf8")));
  if (`${scenario.id}.yaml` !== basename(path)) {
    throw new Error(`${path}: the id '${scenario.id}' does not match the file name`);
  }
  return scenario;
}

/**
 * Loads every scenario in a folder, in file name order, and refuses duplicate ids.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function loadScenarios(folder: string): Scenario[] {
  const files = readdirSync(folder).filter((name) => name.endsWith(".yaml")).sort();
  return files.map((name) => loadScenario(join(folder, name)));
}
