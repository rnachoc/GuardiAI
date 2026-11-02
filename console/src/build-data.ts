/**
 * Builds the replay console's data from the recorded bundles: for each scenario, its alert, the ground truth,
 * the key metric over time (evaluated with the replay's own PromQL), warnings and errors from the logs, the
 * rule baseline's proposal and, where scripted turns exist, GuardiAI's full investigation and plan. The
 * console is for people, so it shows the ground truth, labelled as sealed from the agents.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { existsSync, mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { gunzipSync } from "node:zlib";
import { remediate } from "../../agents/src/remediate.ts";
import { loadScenario } from "../../chaos/src/scenario.ts";
import { runRules } from "../../evals/src/harness.ts";
import { evaluate, parse } from "../../mcp/src/metrics/promql.ts";
import { readToolFile } from "../../mcp/src/shared/replay.ts";
import { untrusted } from "../../mcp/src/shared/untrusted.ts";

/**
 * Reads a sealed truth file of a bundle, which only people and graders may see.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function truth(bundle: string, name: string): any {
  return JSON.parse(gunzipSync(readFileSync(join(bundle, "truth", name))).toString());
}

/**
 * Builds every scenario's entry for the console.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function buildData(root: string) {
  const scenarios = [];
  for (const id of readdirSync(join(root, "chaos", "fixtures")).sort()) {
    const bundle = join(root, "chaos", "fixtures", id);
    const scenario = loadScenario(join(root, "chaos", "scenarios", `${id}.yaml`));
    const run = truth(bundle, "run.json.gz");
    const window = run.window as { start: number; firstFault: number; end: number };
    const alert = readToolFile(bundle, "alert.json.gz");
    const series = readToolFile(bundle, "metrics.json.gz").series;
    const evidence = scenario.groundTruth.evidence.find((e) => e.kind === "metric")!;
    const tree = parse(evidence.query);
    const points: [number, number | null][] = [];
    for (let t = window.start - 30; t <= window.end + 30; t += 15) {
      const value = evaluate(tree, t, series);
      const v = typeof value === "number" ? value : (value[0]?.value ?? 0);
      points.push([t - window.start, Number.isFinite(v) ? Number(v.toPrecision(4)) : null]);
    }
    // Flagged lines first, then lines from the run itself; the recording window starts 60 s early and can
    // hold the tail of the previous scenario.
    const logs = (readToolFile(bundle, "logs.json.gz").lines as any[])
      .filter((l) => l.level === "warn" || l.level === "error")
      .map((l) => ({ l, flagged: untrusted(l.line).flags.length > 0, during: Number(BigInt(l.time) / 1000000000n) >= window.start }))
      .sort((a, b) => Number(b.flagged) - Number(a.flagged) || Number(b.during) - Number(a.during))
      .slice(0, 8).map(({ l }) => l)
      .map((l) => {
        const safe = untrusted(l.line, 240);
        return { offset: Math.round(Number(BigInt(l.time) / 1000000000n) - window.start), service: l.service, level: l.level,
          text: safe.text.replace(/^<<<untrusted |\s?untrusted>>>$/g, ""), flags: safe.flags };
      });
    let guardia = null;
    if (existsSync(join(root, "agents", "responses", `${id}.json`))) {
      const result = await remediate(root, id, join(mkdtempSync(join(tmpdir(), "console-")), "proposals.jsonl"));
      guardia = {
        provenance: JSON.parse(readFileSync(join(root, "agents", "responses", `${id}.json`), "utf8")).provenance,
        steps: result.investigation.steps,
        ledger: result.investigation.ledger.map((e) => ({ id: e.id, step: e.step, tool: e.tool,
          query: String(e.args.query ?? JSON.stringify(e.args)), result: e.result.slice(0, 600), isError: e.isError })),
        diagnosis: result.investigation.diagnosis ?? null,
        problems: result.investigation.problems,
        accepted: result.plan?.accepted.map((a) => ({ action: a.action, target: a.target, reason: a.reason, evidence: a.evidence,
          expectedEffect: a.expectedEffect, verification: a.verificationQuery, rollback: a.rollback })) ?? [],
        refused: result.plan?.refused.map((r) => ({ action: r.draft.action, target: r.draft.target, reason: r.reason })) ?? [],
      };
    }
    const rule = runRules(scenario, 1);
    scenarios.push({
      id, family: scenario.family, kind: scenario.kind, description: scenario.description,
      alert: { name: alert.name, severity: alert.severity, service: alert.service, annotations: alert.annotations,
        offset: alert.firedAt - window.start },
      timeline: { duration: scenario.durationSeconds, faults: scenario.steps.map((s) => ({ type: s.fault.type, at: s.at,
        until: s.fault.type === "plant_log" ? s.at : (s.revertAt ?? scenario.durationSeconds) })) },
      truth: scenario.groundTruth.rootCause,
      acceptable: scenario.groundTruth.acceptableActions, forbidden: scenario.groundTruth.forbiddenActions,
      metric: { label: evidence.id, query: evidence.query, expect: evidence.expect, points },
      logs,
      rules: { proposes: rule.proposals, forbidden: rule.proposals.filter((p) => scenario.groundTruth.forbiddenActions.includes(p)) },
      guardia,
    });
  }
  return { generatedFrom: "chaos/fixtures, agents/responses", scenarios };
}
