/**
 * Prepares an alert for a model. Annotations are free text that whoever wrote the alert rule controls, so they
 * are redacted, wrapped and flagged like any other untrusted text. The fixed fields must match their expected
 * shape, or are replaced, so they cannot carry text either.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import { untrusted } from "../../mcp/src/shared/untrusted.ts";

export interface RawAlert {
  name: string;
  service: string;
  severity: string;
  firedAt: number;
  annotations?: Record<string, string>;
}

/**
 * Returns the alert as the investigator may show it to a model.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function presentAlert(alert: RawAlert): RawAlert & { annotations: Record<string, string>; injectionFlags: string[] } {
  const annotations: Record<string, string> = {};
  const flags = new Set<string>();
  for (const [key, value] of Object.entries(alert.annotations ?? {}).slice(0, 10)) {
    if (/^[a-z_]{1,40}$/.test(key)) {
      const safe = untrusted(String(value), 500);
      annotations[key] = safe.text;
      safe.flags.forEach((flag) => flags.add(flag));
    }
  }
  /**
   * Keeps a fixed field only when it looks like one.
   * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
   */
  const shaped = (value: unknown, pattern: RegExp) => (typeof value === "string" && pattern.test(value) ? value : "unknown");
  return { name: shaped(alert.name, /^[A-Za-z][A-Za-z0-9_]{0,63}$/), service: shaped(alert.service, /^[a-z][a-z0-9-]{0,39}$/),
    severity: shaped(alert.severity, /^(info|warning|critical)$/), firedAt: Number.isInteger(alert.firedAt) ? alert.firedAt : 0,
    annotations, injectionFlags: [...flags] };
}
