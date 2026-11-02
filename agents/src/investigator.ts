/**
 * The investigator: a bounded loop in which a model asks read-only tools for evidence and ends with a
 * diagnosis. Every tool call is written to an evidence ledger under an ID, and the diagnosis may only cite
 * those IDs. Code then validates the diagnosis: a claim without evidence, or citing evidence that does not
 * exist, failed, or no longer gives the same answer when its call is run again, is rejected (brief,
 * principle 2). So is a claim whose only evidence is text a tool server flagged as a possible injection: hostile
 * text may be cited, but never alone. The model proposes; this code decides what counts.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { createHash } from "node:crypto";
import { z } from "zod";

export const SERVICES = ["gateway", "catalog", "cart", "checkout", "payments", "inventory", "notifier", "postgres", "redis", "kafka", "none"] as const;

export const Diagnosis = z.strictObject({
  hypotheses: z.array(z.strictObject({
    rank: z.number().int().min(1),
    component: z.enum(SERVICES),
    mechanism: z.string().regex(/^[a-z]+(_[a-z]+)*$/),
    statement: z.string().min(10).max(1000),
    confidence: z.number().min(0).max(1),
    evidence: z.array(z.string().regex(/^ev-\d+$/)).max(20),
  })).min(1).max(5),
  unknowns: z.array(z.string().max(500)).max(10),
});
export type Diagnosis = z.infer<typeof Diagnosis>;

export interface ToolCall {
  tool: string;
  args: Record<string, unknown>;
}

export interface LedgerEntry extends ToolCall {
  id: string;
  /** The step the call was made in; re-running it as evidence happens at that same moment. */
  step: number;
  isError: boolean;
  result: string;
  sha256: string;
}

/** A tool the investigator may call; it returns the tool's text answer and whether it was an error. */
export type Tool = (args: Record<string, unknown>) => Promise<{ text: string; isError: boolean }>;

export interface Usage {
  inputTokens: number;
  outputTokens: number;
}

/** One turn of the model: either tool calls to make, or the final diagnosis, with what it cost. */
export type Turn = { toolCalls: ToolCall[]; usage: Usage } | { final: unknown; usage: Usage };

export interface Provider {
  name: string;
  next(context: { alert: unknown; ledger: LedgerEntry[] }): Promise<Turn>;
}

/**
 * Raised when an investigation must stop: the step limit, the token budget or the spend cap was reached.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export class BudgetExceeded extends Error {
}

/**
 * Hashes a tool's answer, so evidence can be compared when its call is run again.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function digest(text: string): string {
  return createHash("sha256").update(text).digest("hex");
}

/**
 * Tells whether a tool's answer carries text the tool server flagged as a possible injection.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function flagged(result: string): boolean {
  return /"injectionFlags":\[\s*"/.test(result);
}

/**
 * Checks a diagnosis against the ledger and returns the problems found, or an empty list. Each cited call
 * is run again at the step it was made in (`atStep` moves a replayed clock there), and its answer must be
 * identical to the one the model saw.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function validate(raw: unknown, ledger: LedgerEntry[], tools: Map<string, Tool>,
                               atStep: (step: number) => void = () => {}): Promise<{ diagnosis?: Diagnosis; problems: string[] }> {
  const parsed = Diagnosis.safeParse(raw);
  if (!parsed.success) {
    return { problems: parsed.error.issues.map((issue) => `diagnosis.${issue.path.join(".")}: ${issue.message}`) };
  }
  const diagnosis = parsed.data;
  const problems: string[] = [];
  const ranks = diagnosis.hypotheses.map((h) => h.rank);
  if (ranks.join() !== ranks.map((_, i) => i + 1).join()) {
    problems.push("hypotheses must be ranked 1, 2, 3 … in order");
  }
  for (let i = 1; i < diagnosis.hypotheses.length; i += 1) {
    if ((diagnosis.hypotheses[i]?.confidence ?? 0) > (diagnosis.hypotheses[i - 1]?.confidence ?? 0)) {
      problems.push(`hypothesis ${i + 1} is more confident than the one ranked above it`);
    }
  }
  const byId = new Map(ledger.map((entry) => [entry.id, entry]));
  for (const hypothesis of diagnosis.hypotheses) {
    if (hypothesis.component !== "none" && hypothesis.evidence.length === 0) {
      problems.push(`hypothesis ${hypothesis.rank} makes a claim without evidence`);
    }
    const cited = hypothesis.evidence.map((id) => byId.get(id)).filter((entry) => entry !== undefined);
    if (cited.length > 0 && cited.length === hypothesis.evidence.length && cited.every((entry) => flagged(entry.result))) {
      problems.push(`hypothesis ${hypothesis.rank} rests only on text flagged as a possible injection`);
    }
    for (const id of hypothesis.evidence) {
      const entry = byId.get(id);
      if (!entry) {
        problems.push(`hypothesis ${hypothesis.rank} cites ${id}, which no tool call produced`);
        continue;
      }
      if (entry.isError) {
        problems.push(`hypothesis ${hypothesis.rank} cites ${id}, which was a refused or failed call`);
        continue;
      }
      const tool = tools.get(entry.tool);
      atStep(entry.step);
      const again = tool ? await tool(entry.args) : undefined;
      if (!again || again.isError || digest(again.text) !== entry.sha256) {
        problems.push(`hypothesis ${hypothesis.rank} cites ${id}, whose call no longer gives the same answer`);
      }
    }
  }
  return { diagnosis, problems };
}

export interface Limits {
  maxSteps: number;
  maxTokens: number;
  maxToolCallsPerStep: number;
}

export interface Investigation {
  provider: string;
  steps: number;
  tokens: Usage;
  ledger: LedgerEntry[];
  diagnosis?: Diagnosis;
  problems: string[];
  stoppedBy?: string;
}

/**
 * Runs one investigation: asks the provider for turns, executes the tool calls it requests through the
 * read-only tools, and validates the final diagnosis. Stops at the step limit or the token budget.
 * `onStep` is told each step's number before it runs, which lets a replayed clock advance per step.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export async function investigate(provider: Provider, alert: unknown, tools: Map<string, Tool>, limits: Limits,
                                  onStep: (step: number) => void = () => {}): Promise<Investigation> {
  const ledger: LedgerEntry[] = [];
  const tokens: Usage = { inputTokens: 0, outputTokens: 0 };
  for (let step = 1; step <= limits.maxSteps; step += 1) {
    onStep(step);
    let turn: Turn;
    try {
      turn = await provider.next({ alert, ledger });
    } catch (error) {
      if (error instanceof BudgetExceeded) {
        return { provider: provider.name, steps: step - 1, tokens, ledger, problems: [error.message], stoppedBy: "spend cap" };
      }
      throw error;
    }
    tokens.inputTokens += turn.usage.inputTokens;
    tokens.outputTokens += turn.usage.outputTokens;
    if (tokens.inputTokens + tokens.outputTokens > limits.maxTokens) {
      return { provider: provider.name, steps: step, tokens, ledger, problems: [`token budget of ${limits.maxTokens} exceeded`], stoppedBy: "token budget" };
    }
    if ("final" in turn) {
      const { diagnosis, problems } = await validate(turn.final, ledger, tools, onStep);
      return { provider: provider.name, steps: step, tokens, ledger, diagnosis, problems };
    }
    if (turn.toolCalls.length > limits.maxToolCallsPerStep) {
      return { provider: provider.name, steps: step, tokens, ledger,
        problems: [`step ${step} asked for ${turn.toolCalls.length} tool calls; at most ${limits.maxToolCallsPerStep} are allowed`], stoppedBy: "tool call limit" };
    }
    for (const call of turn.toolCalls) {
      const tool = tools.get(call.tool);
      const answer = tool ? await tool(call.args) : { text: JSON.stringify({ error: `no tool named ${call.tool}` }), isError: true };
      ledger.push({ ...call, id: `ev-${ledger.length + 1}`, step, isError: answer.isError, result: answer.text, sha256: digest(answer.text) });
    }
  }
  return { provider: provider.name, steps: limits.maxSteps, tokens, ledger, problems: [`no diagnosis within ${limits.maxSteps} steps`], stoppedBy: "step limit" };
}
