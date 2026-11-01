/**
 * Tests for the scenario format: every shipped scenario is valid, and each rule rejects what it should.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { strict as assert } from "node:assert";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { parse, stringify } from "yaml";
import { readFileSync } from "node:fs";
import { Scenario, loadScenario, loadScenarios } from "../src/scenario.ts";
import { validate } from "../src/cli.ts";

const SCENARIOS = join(dirname(fileURLToPath(import.meta.url)), "..", "scenarios");

/**
 * Returns a copy of a shipped scenario as plain data, to be broken by a test.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function base(id = "payments-latency"): Record<string, any> {
  return parse(readFileSync(join(SCENARIOS, `${id}.yaml`), "utf8"));
}

/**
 * Returns the messages of the issues a scenario raises, or an empty list when it is valid.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function issues(data: unknown): string[] {
  const result = Scenario.safeParse(data);
  return result.success ? [] : result.error.issues.map((issue) => `${issue.path.join(".")}: ${issue.message}`);
}

test("every shipped scenario is valid and the validator says so", () => {
  const lines: string[] = [];
  assert.equal(validate(SCENARIOS, (line) => lines.push(line)), 0, lines.join("\n"));
  assert.ok(loadScenarios(SCENARIOS).length >= 10);
});

test("an unknown field is refused instead of ignored", () => {
  const data = base();
  data.groundTruth.rootcause = data.groundTruth.rootCause;
  assert.ok(issues(data).length > 0);
});

test("an action outside the catalog, or one without a target, is refused", () => {
  const data = base();
  data.groundTruth.acceptableActions = ["delete_database:postgres"];
  assert.ok(issues(data).some((message) => message.includes("acceptableActions")));
  data.groundTruth.acceptableActions = ["restart_service"];
  assert.ok(issues(data).some((message) => message.includes("acceptableActions")));
  data.groundTruth.acceptableActions = ["restart_service:mainframe"];
  assert.ok(issues(data).some((message) => message.includes("acceptableActions")));
});

test("an action cannot be both acceptable and forbidden", () => {
  const data = base();
  data.groundTruth.forbiddenActions.push("failover:payments");
  assert.ok(issues(data).some((message) => message.includes("both acceptable and forbidden")));
});

test("a decoy may only accept doing nothing", () => {
  const data = base("decoy-catalog-blip");
  data.groundTruth.acceptableActions = ["restart_service:catalog"];
  data.groundTruth.forbiddenActions = [];
  assert.ok(issues(data).some((message) => message.includes("only acceptable action is none")));
});

test("an injection must actually plant hostile text", () => {
  const data = base("injection-annotation-rollback");
  data.alert.annotations = { summary: "More than 5% of POST /checkouts answer 5xx." };
  assert.ok(issues(data).some((message) => message.includes("must plant hostile text")));
});

test("a step must fall inside the scenario and be reverted after it starts", () => {
  const late = base();
  late.steps[0].at = late.durationSeconds;
  assert.ok(issues(late).some((message) => message.includes("within the scenario's duration")));
  const backwards = base();
  backwards.steps[0].revertAt = backwards.steps[0].at;
  assert.ok(issues(backwards).some((message) => message.includes("reverted after it starts")));
});

test("a fault must name a known proxy or service", () => {
  const data = base();
  data.steps[0].fault.proxy = "checkout-mainframe";
  assert.ok(issues(data).length > 0);
});

test("a scenario's id must match its file name", () => {
  const folder = mkdtempSync(join(tmpdir(), "scenarios-"));
  const path = join(folder, "renamed.yaml");
  writeFileSync(path, stringify(base()));
  assert.throws(() => loadScenario(path), /does not match the file name/);
});

test("the validator fails, and names the file, when one scenario is broken", () => {
  const folder = mkdtempSync(join(tmpdir(), "scenarios-"));
  writeFileSync(join(folder, "payments-latency.yaml"), stringify(base()));
  const broken = base("inventory-killed");
  broken.kind = "experiment";
  writeFileSync(join(folder, "inventory-killed.yaml"), stringify(broken));
  const lines: string[] = [];
  assert.equal(validate(folder, (line) => lines.push(line)), 1);
  assert.ok(lines.some((line) => line.startsWith("FAIL  inventory-killed.yaml")));
});
