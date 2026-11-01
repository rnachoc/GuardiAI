/**
 * Connects the investigator to GuardiAI's read-only tool servers over MCP, in replay mode for one recorded
 * bundle. Each tool is exposed as `server.tool`, for example `metrics.query_metrics`.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { createChangesServer } from "../../mcp/src/changes/server.ts";
import { createLogsServer } from "../../mcp/src/logs/server.ts";
import { createMetricsServer } from "../../mcp/src/metrics/server.ts";
import { createRunbooksServer } from "../../mcp/src/runbooks/server.ts";
import { createTracesServer } from "../../mcp/src/traces/server.ts";
import type { Tool } from "./investigator.ts";

/**
 * Connects to every tool server replaying one bundle, and returns its tools by qualified name.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function replayTools(bundle: string, runbooks: string[]): Promise<Map<string, Tool>> {
  const servers: [string, McpServer][] = [
    ["metrics", createMetricsServer({ kind: "replay", bundle })],
    ["logs", createLogsServer({ kind: "replay", bundle })],
    ["traces", createTracesServer({ kind: "replay", bundle })],
    ["changes", createChangesServer({ kind: "replay", bundle })],
    ["runbooks", createRunbooksServer(runbooks)],
  ];
  const tools = new Map<string, Tool>();
  for (const [prefix, server] of servers) {
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
    await server.connect(serverSide);
    const client = new Client({ name: "guardia-investigator", version: "0.4.0" });
    await client.connect(clientSide);
    for (const tool of (await client.listTools()).tools) {
      tools.set(`${prefix}.${tool.name}`, async (args) => {
        const result = await client.callTool({ name: tool.name, arguments: args });
        return { text: (result.content as { text: string }[])[0]?.text ?? "", isError: result.isError === true };
      });
    }
  }
  return tools;
}

/**
 * The default folders of runbooks and past incidents.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function defaultRunbooks(root: string): string[] {
  return [join(root, "docs", "runbooks"), join(root, "docs", "incidents")];
}
