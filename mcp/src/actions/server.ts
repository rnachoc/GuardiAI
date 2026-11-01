/**
 * mcp-actions: the only way an agent can ask for a change, and all it does is write a proposal. It never
 * executes anything, never reaches Docker or a backend, and never judges the proposal: whether the action is
 * in the catalog, allowed on its target and within its blast radius is the policy engine's decision
 * (ADR 2, ADR 15). Proposals are capped per minute, so a runaway loop cannot flood the approval queue.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { randomUUID } from "node:crypto";
import { appendFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { z } from "zod";
import { answer, refuse } from "../shared/server.ts";

export const MAX_PER_MINUTE = 10;

/**
 * Builds the server, writing proposals to a JSON Lines file. Exported so tests can connect to it in memory.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function createActionsServer(proposalsFile: string, now: () => number = () => Date.now()): McpServer {
  const server = new McpServer({ name: "mcp-actions", version: "0.6.0" });
  const recent: number[] = [];

  server.registerTool("propose_action", {
    title: "Propose an action",
    description: "Proposes one action from the catalog on one service, citing the evidence IDs behind it. "
      + "It only creates a proposal and returns its ID; the policy engine decides, and nothing runs from here.",
    inputSchema: {
      action: z.string().regex(/^[a-z_]{1,40}$/),
      target: z.string().regex(/^[a-z-]{1,40}$/),
      reason: z.string().min(10).max(1000),
      evidence: z.array(z.string().regex(/^ev-\d+$/)).min(1).max(20),
      expectedEffect: z.string().min(5).max(500),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
  }, async ({ action, target, reason, evidence, expectedEffect }) => {
    const at = now();
    while (recent.length && (recent[0] ?? 0) <= at - 60000) {
      recent.shift();
    }
    if (recent.length >= MAX_PER_MINUTE) {
      return refuse(`at most ${MAX_PER_MINUTE} proposals a minute; wait before proposing again`);
    }
    recent.push(at);
    const proposal = { id: `prop-${randomUUID()}`, at: new Date(at).toISOString(), action, target, reason, evidence, expectedEffect,
      status: "awaiting_policy" };
    appendFileSync(proposalsFile, `${JSON.stringify(proposal)}\n`);
    return answer({ proposalId: proposal.id, status: proposal.status,
      note: "This is a proposal only. The policy engine decides whether it may run." });
  });

  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await createActionsServer(process.env.PROPOSALS_FILE ?? "/data/proposals.jsonl").connect(new StdioServerTransport());
}
