/**
 * The postmortem writer. It turns one run of the pipeline into a blameless Markdown postmortem: the alert,
 * the ranked hypotheses with the evidence each cites, what is still unknown, the actions proposed with their
 * verification and rollback, the drafts refused and why, and the evidence ledger. It is plain code, not a
 * model: every line comes from the investigation, and tool output is never repeated, only the call that
 * produced it and its hash, so untrusted text from logs cannot travel into the document.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import type { Remediation } from "./remediate.ts";

export interface Alert {
  name: string;
  service: string;
  severity: string;
  firedAt: number;
  annotations?: { summary?: string };
}

/**
 * Makes text safe for one Markdown table cell or line: no newlines, no pipes, no HTML.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
function cell(text: string): string {
  return text.replace(/\s+/g, " ").replaceAll("|", "\\|").replaceAll("<", "&lt;").replaceAll(">", "&gt;").trim();
}

/**
 * Writes the postmortem for one scenario's run of the pipeline.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function writePostmortem(scenario: string, alert: Alert, result: Remediation): string {
  const { investigation, plan } = result;
  const diagnosis = investigation.diagnosis;
  const fired = new Date(alert.firedAt * 1000).toISOString().replace(/\.\d{3}Z$/, "Z");
  const lines = [
    `# Postmortem: ${cell(alert.name)} on ${cell(alert.service)}`,
    "",
    `Scenario \`${scenario}\`. Written by GuardiAI's postmortem writer from the evidence ledger; blameless by construction.`,
    "",
    "## Alert",
    "",
    `- Fired: ${fired}`,
    `- Severity: ${cell(alert.severity)}`,
    `- Summary: ${cell(alert.annotations?.summary ?? "none given")}`,
    "",
    "## What GuardiAI concluded",
    "",
  ];
  if (!diagnosis || investigation.problems.length > 0) {
    lines.push("The diagnosis did not validate, so no action was planned. The problems found:", "",
      ...investigation.problems.map((p) => `- ${cell(p)}`));
  } else {
    lines.push("| Rank | Component | Mechanism | Confidence | Evidence | Statement |", "| --- | --- | --- | --- | --- | --- |",
      ...diagnosis.hypotheses.map((h) => `| ${h.rank} | ${h.component} | ${h.mechanism} | ${h.confidence.toFixed(2)} | ${h.evidence.join(", ") || "none"} | ${cell(h.statement)} |`));
    lines.push("", "### Still unknown", "", ...(diagnosis.unknowns.length ? diagnosis.unknowns.map((u) => `- ${cell(u)}`) : ["- Nothing was recorded as unknown."]));
  }
  lines.push("", "## Actions", "");
  if (!plan || plan.accepted.length === 0) {
    lines.push("No action was proposed.");
  } else {
    for (const a of plan.accepted) {
      lines.push(`- **${a.action} on ${a.target}**, citing ${a.evidence.join(", ")}. ${cell(a.reason)}`,
        `  - Expected effect: ${cell(a.expectedEffect)}`, `  - Verification: \`${a.verificationQuery.replaceAll("`", "'")}\``,
        `  - Rollback: ${cell(a.rollback)}`);
    }
    lines.push("", "Whether each proposal ran is the policy service's decision; its audit log holds the outcome.");
  }
  if (plan && plan.refused.length > 0) {
    lines.push("", "### Drafts the planner gate refused", "", ...plan.refused.map((r) => `- ${r.draft.action} on ${cell(r.draft.target)}: ${cell(r.reason)}`));
  }
  lines.push("", "## Evidence", "", `${investigation.steps} steps, ${investigation.ledger.length} tool calls, ${investigation.tokens.inputTokens + investigation.tokens.outputTokens} tokens. Tool output is not repeated here; run the call again to see it.`, "",
    "| ID | Step | Tool | Arguments | Result | SHA-256 |", "| --- | --- | --- | --- | --- | --- |",
    ...investigation.ledger.map((e) => `| ${e.id} | ${e.step} | ${cell(e.tool)} | \`${cell(JSON.stringify(e.args)).replaceAll("`", "'")}\` | ${e.isError ? "error" : "ok"} | ${e.sha256.slice(0, 12)} |`));
  return lines.join("\n") + "\n";
}
