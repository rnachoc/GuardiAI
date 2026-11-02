/**
 * guardia-investigate: runs the investigator on one recorded scenario. With the default fake provider it
 * replays agents/responses/<scenario>.json and costs nothing. With GUARDIA_MODEL_PROVIDER=anthropic it asks
 * Claude, never spending more than GUARDIA_MAX_SPEND_USD, and records the turns for later replay.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { existsSync, readFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { AnthropicProvider, OPUS_PRICES } from "./anthropic.ts";
import { presentAlert } from "./alert.ts";
import { investigate } from "./investigator.ts";
import { FakeProvider, SpendCap } from "./providers.ts";
import { exportInvestigation } from "./telemetry.ts";
import { defaultRunbooks, replayToolset } from "./tools.ts";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const LIMITS = { maxSteps: 12, maxTokens: 400000, maxToolCallsPerStep: 6 };

/**
 * Runs one investigation and prints its result; returns the exit code.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
async function main(scenario: string | undefined): Promise<number> {
  if (!scenario || !/^[a-z0-9-]+$/.test(scenario)) {
    console.error("usage: guardia-investigate <scenario-id>");
    return 2;
  }
  const bundle = join(ROOT, "chaos", "fixtures", scenario);
  if (!existsSync(join(bundle, "manifest.json"))) {
    console.error(`no recording of ${scenario} in chaos/fixtures; record it first with make record SCENARIO=${scenario}`);
    return 2;
  }
  const alert = presentAlert(JSON.parse(gunzipSync(readFileSync(join(bundle, "alert.json.gz"))).toString()));
  const { tools, definitions } = await replayToolset(bundle, defaultRunbooks(ROOT));
  const responses = join(ROOT, "agents", "responses", `${scenario}.json`);
  const real = process.env.GUARDIA_MODEL_PROVIDER === "anthropic";
  const anthropic = real ? new AnthropicProvider(definitions) : undefined;
  const capped = anthropic ? new SpendCap(anthropic, Number(process.env.GUARDIA_MAX_SPEND_USD ?? "1"), OPUS_PRICES) : undefined;
  const provider = capped ?? new FakeProvider(responses);
  const began = Date.now();
  const result = await investigate(provider, alert, tools, LIMITS);
  if (await exportInvestigation(process.env.OTEL_EXPORTER_OTLP_ENDPOINT, scenario, result, Date.now() - began)) {
    console.log(`sent the investigation's trace and metrics to ${process.env.OTEL_EXPORTER_OTLP_ENDPOINT}`);
  }
  if (anthropic) {
    anthropic.saveRecording(responses, scenario);
    console.log(`recorded the turns to ${responses}; spent about $${capped?.spent().toFixed(4)}`);
  }
  console.log(JSON.stringify({ provider: result.provider, steps: result.steps, tokens: result.tokens, toolCalls: result.ledger.length,
    stoppedBy: result.stoppedBy, problems: result.problems, diagnosis: result.diagnosis }, null, 2));
  return result.problems.length === 0 ? 0 : 1;
}

process.exitCode = await main(process.argv[2]);
