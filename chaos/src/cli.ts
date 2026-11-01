#!/usr/bin/env node
/**
 * guardia-chaos: the command line for Guardia's incident scenarios. It validates scenarios, runs one
 * against the live stack, and resets every fault. The snapshot recorder is being built (PROGRESS.md).
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { ZodError } from "zod";
import { loadScenario, type Scenario } from "./scenario.ts";
import { mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { endpointsFromEnvironment, runScenario } from "./run.ts";
import { resetToxics } from "./stack.ts";

const DEFAULT_FOLDER = join(dirname(fileURLToPath(import.meta.url)), "..", "scenarios");

/**
 * Validates every scenario in a folder, prints one line per scenario, and returns the process exit code.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function validate(folder: string, print: (line: string) => void = console.log): number {
  const files = readdirSync(folder).filter((name) => name.endsWith(".yaml")).sort();
  const valid: Scenario[] = [];
  let failures = 0;
  for (const file of files) {
    try {
      const scenario = loadScenario(join(folder, file));
      valid.push(scenario);
      print(`ok    ${scenario.id.padEnd(34)} ${scenario.family.padEnd(14)} ${scenario.kind}`);
    } catch (error) {
      failures += 1;
      const detail = error instanceof ZodError
        ? error.issues.map((issue) => `${issue.path.join(".")}: ${issue.message}`).join("; ")
        : String(error);
      print(`FAIL  ${file}: ${detail}`);
    }
  }
  const ids = valid.map((scenario) => scenario.id);
  const duplicates = ids.filter((id, index) => ids.indexOf(id) !== index);
  if (duplicates.length > 0) {
    failures += 1;
    print(`FAIL  duplicate ids: ${duplicates.join(", ")}`);
  }
  /**
   * Counts the valid scenarios of one kind.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const count = (kind: string) => valid.filter((scenario) => scenario.kind === kind).length;
  print(`${valid.length} valid, ${failures} invalid: ${count("incident")} incidents, ${count("decoy")} decoys, `
    + `${count("injection")} injections, across ${new Set(valid.map((scenario) => scenario.family)).size} families`);
  return failures === 0 && files.length > 0 ? 0 : 1;
}

/**
 * Runs a command from the arguments and returns the exit code.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function main(args: string[]): Promise<number> {
  const [command, argument] = args;
  if (command === "validate") {
    return validate(argument ?? DEFAULT_FOLDER);
  }
  if (command === "run" && argument) {
    const scenario = loadScenario(join(DEFAULT_FOLDER, `${argument}.yaml`));
    const controller = new AbortController();
    process.once("SIGINT", () => controller.abort());
    process.once("SIGTERM", () => controller.abort());
    console.log(`running ${scenario.id} for ${scenario.durationSeconds}s`);
    const record = await runScenario(scenario, endpointsFromEnvironment(), controller.signal);
    const folder = process.env.RUNS_FOLDER ?? join(DEFAULT_FOLDER, "..", "runs");
    mkdirSync(folder, { recursive: true });
    const path = join(folder, `${scenario.id}.json`);
    writeFileSync(path, `${JSON.stringify(record, null, 2)}\n`);
    console.log(`${record.interrupted ? "interrupted; every fault reverted" : "done"}; run record in ${path}`);
    return record.interrupted ? 130 : 0;
  }
  if (command === "reset") {
    await resetToxics(endpointsFromEnvironment());
    console.log("every toxic removed");
    return 0;
  }
  console.error("usage: guardia-chaos validate [folder] | run <scenario-id> | reset");
  return 2;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
