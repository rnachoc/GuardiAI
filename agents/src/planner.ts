/**
 * The remediation planner's gate. A model drafts proposals from a validated diagnosis; this code keeps only
 * those that name a catalog action on a target it allows, act on a component a hypothesis names, cite
 * evidence that exists in the ledger, and say what they should achieve. A diagnosis of "none" admits no
 * proposal at all. Requiring the target to be a diagnosed component stops a confused deputy: text that
 * talks an agent into acting on some other service cannot also supply the diagnosis for it. The proposals that pass are
 * sent through mcp-actions, which records them for the policy engine; nothing here can run an action.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { readFileSync } from "node:fs";
import { parse } from "yaml";
import type { Diagnosis, LedgerEntry, Tool } from "./investigator.ts";

export interface CatalogAction {
  name: string;
  targets: string[];
  verification: { query: string };
  autoRevert: string;
}

export interface Draft {
  action: string;
  target: string;
  reason: string;
  evidence: string[];
  expectedEffect: string;
}

export interface Plan {
  accepted: (Draft & { verificationQuery: string; rollback: string })[];
  refused: { draft: Draft; reason: string }[];
}

/**
 * Reads the policy engine's catalog, so the planner and the engine share one list of actions.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function loadCatalog(path: string): CatalogAction[] {
  return (parse(readFileSync(path, "utf8")) as { actions: CatalogAction[] }).actions;
}

/**
 * Sorts drafts into accepted and refused, each refusal with its reason.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function plan(diagnosis: Diagnosis, drafts: Draft[], ledger: LedgerEntry[], catalog: CatalogAction[]): Plan {
  const result: Plan = { accepted: [], refused: [] };
  const known = new Set(ledger.filter((e) => !e.isError).map((e) => e.id));
  const top = diagnosis.hypotheses[0];
  for (const draft of drafts) {
    const action = catalog.find((a) => a.name === draft.action);
    let reason = "";
    if (!top || top.component === "none") {
      reason = "the diagnosis found nothing to fix, so no action is proposed";
    } else if (!action) {
      reason = `${draft.action} is not in the catalog`;
    } else if (!action.targets.includes(draft.target)) {
      reason = `${draft.action} cannot target ${draft.target}`;
    } else if (!diagnosis.hypotheses.some((h) => h.component === draft.target)) {
      reason = `no hypothesis names ${draft.target}, so no action may target it`;
    } else if (draft.evidence.length === 0 || draft.evidence.some((id) => !known.has(id))) {
      reason = "a proposal must cite evidence that exists in the ledger";
    } else if (draft.expectedEffect.trim().length < 5) {
      reason = "a proposal must say what it should achieve";
    }
    if (reason || !action) {
      result.refused.push({ draft, reason });
    } else {
      result.accepted.push({ ...draft, verificationQuery: action.verification.query.replaceAll("$target", draft.target),
        rollback: action.autoRevert });
    }
  }
  return result;
}

/**
 * Sends each accepted proposal through mcp-actions and returns the proposal IDs it gave back.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function submit(accepted: Plan["accepted"], proposeAction: Tool): Promise<string[]> {
  const ids: string[] = [];
  for (const p of accepted) {
    const answer = await proposeAction({ action: p.action, target: p.target, reason: p.reason, evidence: p.evidence, expectedEffect: p.expectedEffect });
    if (!answer.isError) {
      ids.push(JSON.parse(answer.text).proposalId as string);
    }
  }
  return ids;
}
