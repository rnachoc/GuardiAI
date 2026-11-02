/**
 * The remediation pipeline in replay: investigate, then, only if the diagnosis validates, gate the planner's
 * drafts and send the ones that pass through mcp-actions. What happens to them next is the policy service's
 * decision. Drafts come from a file with its provenance, scripted by hand under ADR 16.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createActionsServer } from "../../mcp/src/actions/server.ts";
import { investigate, type Investigation, type Tool } from "./investigator.ts";
import { loadCatalog, plan, submit, type Draft, type Plan } from "./planner.ts";
import { FakeProvider } from "./providers.ts";
import { defaultRunbooks, replayTools } from "./tools.ts";

export interface Remediation {
  investigation: Investigation;
  plan?: Plan;
  proposalIds: string[];
}

/**
 * Runs the pipeline for one recorded scenario, with its scripted turns and drafts.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function remediate(root: string, scenario: string, proposalsFile: string, secondsPerStep = 120): Promise<Remediation> {
  let step = 1;
  const tools = await replayTools(join(root, "chaos", "fixtures", scenario), defaultRunbooks(root), () => (step - 1) * secondsPerStep);
  const investigation = await investigate(new FakeProvider(join(root, "agents", "responses", `${scenario}.json`)), {}, tools,
    { maxSteps: 12, maxTokens: 400000, maxToolCallsPerStep: 6 }, (s) => { step = s; });
  if (!investigation.diagnosis || investigation.problems.length > 0) {
    return { investigation, proposalIds: [] };
  }
  const drafts = (JSON.parse(readFileSync(join(root, "agents", "responses", `${scenario}.plan.json`), "utf8")) as { drafts: Draft[] }).drafts;
  const gated = plan(investigation.diagnosis, drafts, investigation.ledger,
    loadCatalog(join(root, "policy", "src", "main", "resources", "catalog-v1.yaml")));
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await createActionsServer(proposalsFile).connect(serverSide);
  const client = new Client({ name: "guardia-planner", version: "0.6.0" });
  await client.connect(clientSide);
  /**
   * Calls propose_action through MCP.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const propose: Tool = async (args) => {
    const result = await client.callTool({ name: "propose_action", arguments: args });
    return { text: (result.content as { text: string }[])[0]?.text ?? "", isError: result.isError === true };
  };
  return { investigation, plan: gated, proposalIds: await submit(gated.accepted, propose) };
}
