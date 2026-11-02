/**
 * make eval: runs the harness, writes evals/REPORT.md, and fails when a threshold is crossed. With --check
 * it writes nothing and fails when the committed report differs from what the harness generates.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";
import { evaluate } from "./harness.ts";
import { gate, render } from "./report.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const REPORT = join(ROOT, "evals", "REPORT.md");

/**
 * Runs the evaluation and returns the exit code.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function main(check: boolean): Promise<number> {
  const runs = Number(process.env.EVAL_RUNS ?? "3");
  const rows = await evaluate(ROOT, runs);
  const report = render(rows, runs);
  const problems = gate(rows, parse(readFileSync(join(ROOT, "evals", "thresholds.yaml"), "utf8")));
  if (check) {
    if (readFileSync(REPORT, "utf8") !== report) {
      problems.push("evals/REPORT.md is out of date: run make eval and commit it");
    }
  } else {
    writeFileSync(REPORT, report);
    console.log(`wrote ${REPORT}`);
  }
  for (const problem of problems) {
    console.error(`FAIL  ${problem}`);
  }
  console.log(problems.length === 0 ? "eval: every threshold holds" : `eval: ${problems.length} problem(s)`);
  return problems.length === 0 ? 0 : 1;
}

process.exitCode = await main(process.argv.includes("--check"));
