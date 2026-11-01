/**
 * Text from the observed system is data, never instructions (brief, principle 3). Every log line, trace
 * attribute or annotation a tool returns is wrapped in delimiters, scanned for known injection patterns,
 * and stripped of secrets and personal data before an agent sees it.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */

/** Phrases that try to talk to the model instead of describing the system. */
const INJECTION_PATTERNS: [string, RegExp][] = [
  ["ignore instructions", /\bignore\b.{0,40}\b(instruction|evidence|previous|above|rules?)/i],
  ["addresses the AI", /\b(on-?call ai|assistant|language model|llm|agent)\b.{0,30}[:,]/i],
  ["claims authority", /\b(system( note)?|maintenance mode|developer mode|admin override)\s*[:-]/i],
  ["pre-approval", /\b(pre-?approved|already approved|mark (it )?as approved|skip (the )?(policy|approval|check))/i],
  ["orders an action", /\b(propose|run|execute|perform)\b.{0,30}\b(restart_service|rollback_deploy|failover|scale_deployment|toggle_flag|clear_cache|pause_consumer|resume_consumer|increase_pool_size)\b/i],
];

/** Secrets and personal data that must not reach a model, replaced by a marker. */
const REDACTIONS: [string, RegExp][] = [
  ["aws-key", /\b(AKIA|ASIA)[0-9A-Z]{16}\b/g],
  ["private-key", /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g],
  ["bearer-token", /\bBearer\s+[A-Za-z0-9._~+/-]{20,}/g],
  ["api-key", /\b(sk-ant-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,})\b/g],
  ["password", /\b(password|passwd|pwd|secret)\s*[=:]\s*[^\s"',}]+/gi],
  ["email", /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g],
  ["card-number", /\b(?:\d[ -]?){13,19}\b/g],
];

export interface Untrusted {
  text: string;
  flags: string[];
  redacted: string[];
}

/**
 * Prepares a piece of untrusted text for a model: secrets and personal data redacted, injection patterns
 * named in `flags`, and the text wrapped in delimiters that the agents are told to treat as data.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function untrusted(raw: string, maxLength = 2000): Untrusted {
  let text = raw.length > maxLength ? `${raw.slice(0, maxLength)}…[truncated]` : raw;
  const redacted: string[] = [];
  for (const [name, pattern] of REDACTIONS) {
    const replaced = text.replace(pattern, `[redacted:${name}]`);
    if (replaced !== text) {
      redacted.push(name);
      text = replaced;
    }
  }
  const flags = INJECTION_PATTERNS.filter(([, pattern]) => pattern.test(text)).map(([name]) => name);
  // Delimiters inside the text are neutralised, so a line cannot close its own wrapper.
  const safe = text.replaceAll("<<<", "‹‹‹").replaceAll(">>>", "›››");
  return { text: `<<<untrusted ${safe} untrusted>>>`, flags, redacted };
}
