#!/usr/bin/env node
/**
 * guardia-chaos: the command line for Guardia's incident scenarios. This version validates scenarios; the
 * injector and the snapshot recorder are being built (PROGRESS.md, Phase 2).
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { ZodError } from "zod";
import { loadScenario, type Scenario } from "./scenario.ts";
import { readdirSync } from "node:fs";

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
 * Runs a command from the arguments.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function main(args: string[]): number {
  const [command, folder] = args;
  if (command === "validate") {
    return validate(folder ?? DEFAULT_FOLDER);
  }
  console.error("usage: guardia-chaos validate [folder]");
  return 2;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
