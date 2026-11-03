/**
 * guardia-postmortem: runs the pipeline in replay on one recorded scenario and prints its postmortem.
 * It costs nothing: the turns and drafts are the scripted ones (ADR 16). With `--audit <file>`, a saved
 * `GET /audit` answer, it adds the policy service's timeline for the run's proposals.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import { writePostmortem } from "./postmortem.ts";
import { remediate } from "./remediate.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

/**
 * Prints the postmortem of one scenario; returns the exit code.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function main(scenario: string | undefined): Promise<number> {
  if (!scenario || !/^[a-z0-9-]+$/.test(scenario) || !existsSync(join(ROOT, "agents", "responses", `${scenario}.plan.json`))) {
    console.error("usage: guardia-postmortem <scenario-id>, for a scenario with scripted turns and drafts in agents/responses");
    return 2;
  }
  const alert = JSON.parse(gunzipSync(readFileSync(join(ROOT, "chaos", "fixtures", scenario, "alert.json.gz"))).toString());
  const result = await remediate(ROOT, scenario, join(mkdtempSync(join(tmpdir(), "postmortem-")), "proposals.jsonl"));
  const auditFlag = process.argv.indexOf("--audit");
  const audit = auditFlag > 0 && process.argv[auditFlag + 1] ? JSON.parse(readFileSync(process.argv[auditFlag + 1]!, "utf8")) : undefined;
  process.stdout.write(writePostmortem(scenario, alert, result, audit));
  return 0;
}

process.exitCode = await main(process.argv[2]);
