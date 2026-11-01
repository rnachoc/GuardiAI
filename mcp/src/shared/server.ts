/**
 * What every GuardiAI tool server shares: how it learns its mode (live or replay), how it answers in
 * JSON, and how it turns a refused request into an error the agent can read.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

export type Mode = { kind: "live"; baseUrl: string } | { kind: "replay"; bundle: string };

/**
 * Reads the mode from the environment: GUARDIA_TOOLS_MODE=replay with GUARDIA_BUNDLE, or live with the
 * backend's URL from the given variable.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function modeFromEnvironment(urlVariable: string, defaultUrl: string, env: NodeJS.ProcessEnv = process.env): Mode {
  if ((env.GUARDIA_TOOLS_MODE ?? "replay") === "replay") {
    if (!env.GUARDIA_BUNDLE) {
      throw new Error("replay mode needs GUARDIA_BUNDLE, the folder of a recorded scenario");
    }
    return { kind: "replay", bundle: env.GUARDIA_BUNDLE };
  }
  return { kind: "live", baseUrl: env[urlVariable] ?? defaultUrl };
}

/**
 * Wraps a result as the tool's JSON answer.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function answer(value: unknown): CallToolResult {
  return { content: [{ type: "text", text: JSON.stringify(value) }] };
}

/**
 * Wraps a refusal or failure as an error the agent reads, without a stack trace.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function refuse(reason: string): CallToolResult {
  return { content: [{ type: "text", text: JSON.stringify({ error: reason }) }], isError: true };
}

/** Every tool is read-only and touches nothing outside its backend. */
export const READ_ONLY = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false } as const;
