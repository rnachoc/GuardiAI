/**
 * Model providers for the investigator. The fake provider replays a file of turns, so CI and any clone run
 * the whole investigation for free and deterministically (brief, principle 6). A spend cap wraps any
 * provider that costs money and aborts before the cap would be crossed.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { readFileSync } from "node:fs";
import { z } from "zod";
import { BudgetExceeded, type Provider, type Turn } from "./investigator.ts";

const TurnFile = z.strictObject({
  scenario: z.string(),
  provenance: z.string().min(20).describe("where these turns came from: recorded from a named model, or scripted by hand"),
  turns: z.array(z.union([
    z.strictObject({ toolCalls: z.array(z.strictObject({ tool: z.string(), args: z.record(z.string(), z.unknown()) })),
      usage: z.strictObject({ inputTokens: z.number().int().min(0), outputTokens: z.number().int().min(0) }) }),
    z.strictObject({ final: z.unknown(), usage: z.strictObject({ inputTokens: z.number().int().min(0), outputTokens: z.number().int().min(0) }) }),
  ])).min(1),
});

/**
 * A provider that replays turns from a file, in order. It never calls a model and costs nothing.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export class FakeProvider implements Provider {
  readonly name: string;
  readonly provenance: string;
  private readonly turns: Turn[];
  private position = 0;

  /**
   * Loads the turns.
   * @param path the JSON file of turns
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  constructor(path: string) {
    const file = TurnFile.parse(JSON.parse(readFileSync(path, "utf8")));
    this.name = `fake:${file.scenario}`;
    this.provenance = file.provenance;
    this.turns = file.turns as Turn[];
  }

  /**
   * Returns the next turn, or fails when the file has run out.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  async next(): Promise<Turn> {
    const turn = this.turns[this.position];
    if (!turn) {
      throw new Error(`${this.name} has no turn ${this.position + 1}`);
    }
    this.position += 1;
    return turn;
  }
}

/**
 * Prices in US dollars per million tokens, for a provider that charges.
 */
export interface Prices {
  inputPerMillion: number;
  outputPerMillion: number;
}

/**
 * Wraps a provider with a hard spend cap. Before each turn it checks that the worst case, the spend so far
 * plus one more turn of the largest size seen, stays under the cap, and aborts otherwise.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export class SpendCap implements Provider {
  readonly name: string;
  private readonly inner: Provider;
  private readonly capUsd: number;
  private readonly prices: Prices;
  private spentUsd = 0;
  private largestTurnUsd = 0;

  /**
   * Creates the cap.
   * @param inner the provider that costs money
   * @param capUsd the most it may spend, from GUARDIA_MAX_SPEND_USD
   * @param prices what it charges
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  constructor(inner: Provider, capUsd: number, prices: Prices) {
    if (!(capUsd > 0)) {
      throw new Error("GUARDIA_MAX_SPEND_USD must be a positive number of dollars");
    }
    this.inner = inner;
    this.capUsd = capUsd;
    this.prices = prices;
    this.name = inner.name;
  }

  /**
   * Asks the inner provider for a turn, unless the cap could be crossed by it.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  async next(context: Parameters<Provider["next"]>[0]): Promise<Turn> {
    if (this.spentUsd + this.largestTurnUsd > this.capUsd) {
      throw new BudgetExceeded(`spend cap of $${this.capUsd} reached: $${this.spentUsd.toFixed(4)} spent`);
    }
    const turn = await this.inner.next(context);
    const cost = (turn.usage.inputTokens * this.prices.inputPerMillion + turn.usage.outputTokens * this.prices.outputPerMillion) / 1e6;
    this.spentUsd += cost;
    this.largestTurnUsd = Math.max(this.largestTurnUsd, cost);
    return turn;
  }

  /**
   * Returns what has been spent so far, in US dollars.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  spent(): number {
    return this.spentUsd;
  }
}
