/**
 * Turns a scenario's steps into an ordered list of timed actions: apply each fault, and revert it at its
 * revert time or at the end. Pure, so the plan can be tested without touching the stack.
 *
 * Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
import type { Scenario } from "./scenario.ts";

export type Fault = Scenario["steps"][number]["fault"];

export interface TimedAction {
  atSeconds: number;
  action: "apply" | "revert";
  step: number;
  fault: Fault;
}

/**
 * Plans every apply and revert, sorted by time; at the same second a revert comes before an apply.
 * A planted log is a single event and has nothing to revert.
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
export function plan(scenario: Scenario): TimedAction[] {
  const actions: TimedAction[] = [];
  scenario.steps.forEach((step, index) => {
    actions.push({ atSeconds: step.at, action: "apply", step: index, fault: step.fault });
    if (step.fault.type !== "plant_log") {
      actions.push({ atSeconds: step.revertAt ?? scenario.durationSeconds, action: "revert", step: index, fault: step.fault });
    }
  });
  return actions.sort((a, b) => a.atSeconds - b.atSeconds
    || (a.action === b.action ? a.step - b.step : a.action === "revert" ? -1 : 1));
}
