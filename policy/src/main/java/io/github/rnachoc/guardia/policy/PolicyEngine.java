package io.github.rnachoc.guardia.policy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides every proposal. The model proposes; this class decides (ADR 2). A proposal is rejected when the
 * kill switch is on, its action is not in the catalog, its target is not allowed, a precondition fails or is
 * unknown, the action ran too often in the last hour, or its blast radius is too large. Otherwise it needs
 * approval, unless the engine is autonomous and the action is low risk.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class PolicyEngine {

    private final ActionCatalog catalog;
    private final DependencyGraph graph;
    private final Mode mode;
    private final boolean killSwitch;

    /**
     * Creates the engine.
     *
     * @param catalog the allowed actions
     * @param graph the service dependency graph
     * @param mode suggest, the default where every action needs a person, or autonomous
     * @param killSwitch when on, every proposal is rejected
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public PolicyEngine(ActionCatalog catalog, DependencyGraph graph, Mode mode, boolean killSwitch) {
        this.catalog = catalog;
        this.graph = graph;
        this.mode = mode;
        this.killSwitch = killSwitch;
    }

    /**
     * Judges one proposal, gathering every reason to reject it rather than stopping at the first.
     *
     * @param proposal the proposal
     * @return the decision
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Decision decide(Proposal proposal) {
        if (killSwitch) {
            return new Decision(proposal.id(), Decision.Verdict.REJECTED, List.of("the kill switch is on"), Set.of(), 0);
        }
        Optional<ActionCatalog.Action> found = catalog.find(proposal.action());
        if (found.isEmpty()) {
            return new Decision(proposal.id(), Decision.Verdict.REJECTED,
                    List.of("'" + proposal.action() + "' is not in the catalog"), Set.of(), 0);
        }
        ActionCatalog.Action action = found.get();
        List<String> reasons = new ArrayList<>();
        if (!action.targets().contains(proposal.target())) {
            reasons.add(action.name() + " cannot target " + proposal.target());
        }
        for (String precondition : action.preconditions()) {
            Boolean holds = proposal.facts().get(precondition);
            if (holds == null) {
                reasons.add("precondition " + precondition + " is unknown");
            } else if (!holds) {
                reasons.add("precondition " + precondition + " does not hold");
            }
        }
        long lastHour = proposal.recentRuns().stream()
                .filter(run -> !run.isBefore(proposal.at().minus(Duration.ofHours(1))) && !run.isAfter(proposal.at()))
                .count();
        if (lastHour >= action.maxPerHour()) {
            reasons.add(action.name() + " already ran " + lastHour + " times in the last hour; the limit is " + action.maxPerHour());
        }
        Set<String> radius = graph.blastRadius(proposal.target());
        if (radius.size() > action.maxBlastRadius()) {
            reasons.add("a blast radius of " + radius.size() + " services " + radius + " exceeds " + action.maxBlastRadius());
        }
        int approvals = switch (action.tier()) {
            case "low" -> mode == Mode.AUTONOMOUS ? 0 : 1;
            case "medium" -> 1;
            default -> 2;
        };
        if (!reasons.isEmpty()) {
            return new Decision(proposal.id(), Decision.Verdict.REJECTED, List.copyOf(reasons), radius, approvals);
        }
        Decision.Verdict verdict = approvals == 0 ? Decision.Verdict.APPROVED : Decision.Verdict.NEEDS_APPROVAL;
        return new Decision(proposal.id(), verdict, List.of(action.tier() + " risk, every check passed"), radius, approvals);
    }

    /**
     * How much the engine may do without a person.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public enum Mode {
        SUGGEST,
        AUTONOMOUS
    }
}
