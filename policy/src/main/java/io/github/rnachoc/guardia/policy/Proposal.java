package io.github.rnachoc.guardia.policy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A typed proposal from an agent: what to do, to which service, and the facts the engine needs to judge it.
 * It is only a request; the engine decides.
 *
 * @param id the proposal's identifier
 * @param action the action's name, which must be in the catalog
 * @param target the service to act on
 * @param facts the current state of each precondition, gathered by code, never by the agent
 * @param recentRuns when this action last ran on any target
 * @param at when the proposal is judged
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Proposal(String id, String action, String target, Map<String, Boolean> facts, List<Instant> recentRuns, Instant at) {
}
