/**
 * Runs a scenario against the live stack: applies and reverts each fault on time, reverts everything on
 * interruption, and returns a run record with what happened when. The snapshot recorder reads the record
 * to know which time window to capture.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { setTimeout as sleep } from "node:timers/promises";
import { apply, revert, type Endpoints } from "./stack.ts";
import { plan } from "./timeline.ts";
import type { Scenario } from "./scenario.ts";

export interface RunRecord {
  scenario: string;
  startedAt: string;
  endedAt: string;
  events: { at: string; offsetSeconds: number; action: "apply" | "revert"; step: number; fault: string }[];
  interrupted: boolean;
}

/**
 * Reads where the stack's APIs are from the environment, defaulting to the Compose network.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function endpointsFromEnvironment(env: NodeJS.ProcessEnv = process.env): Endpoints {
  return {
    toxiproxyUrl: env.TOXIPROXY_URL ?? "http://toxiproxy:8474",
    lokiUrl: env.LOKI_URL ?? "http://loki:3100",
    dockerSocket: env.DOCKER_SOCKET ?? "/var/run/docker.sock",
    composeProject: env.COMPOSE_PROJECT ?? "guardia",
  };
}

/**
 * Runs a scenario to its end, or until the signal aborts it, and reverts whatever is still applied.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function runScenario(scenario: Scenario, endpoints: Endpoints, signal?: AbortSignal,
                                  log: (line: string) => void = console.log): Promise<RunRecord> {
  const started = Date.now();
  const record: RunRecord = { scenario: scenario.id, startedAt: new Date(started).toISOString(), endedAt: "", events: [], interrupted: false };
  const applied = new Map<number, Scenario["steps"][number]["fault"]>();
  try {
    for (const action of plan(scenario)) {
      const wait = started + action.atSeconds * 1000 - Date.now();
      if (wait > 0) {
        await sleep(wait, undefined, { signal });
      }
      if (action.action === "apply") {
        await apply(endpoints, action.fault, action.step);
        applied.set(action.step, action.fault);
      } else {
        await revert(endpoints, action.fault, action.step);
        applied.delete(action.step);
      }
      const offset = Math.round((Date.now() - started) / 100) / 10;
      record.events.push({ at: new Date().toISOString(), offsetSeconds: offset, action: action.action, step: action.step, fault: action.fault.type });
      log(`${String(offset).padStart(6)}s  ${action.action.padEnd(6)} step ${action.step}: ${action.fault.type}`);
    }
    const remaining = started + scenario.durationSeconds * 1000 - Date.now();
    if (remaining > 0) {
      await sleep(remaining, undefined, { signal });
    }
  } catch (error) {
    if ((error as Error).name !== "AbortError") {
      throw error;
    }
    record.interrupted = true;
  } finally {
    for (const [step, fault] of applied) {
      await revert(endpoints, fault, step);
      record.events.push({ at: new Date().toISOString(), offsetSeconds: Math.round((Date.now() - started) / 100) / 10,
        action: "revert", step, fault: fault.type });
    }
    record.endedAt = new Date().toISOString();
  }
  return record;
}
