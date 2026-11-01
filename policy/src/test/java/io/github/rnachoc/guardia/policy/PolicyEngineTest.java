package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Written before the engine: what it must refuse, what it may send for approval, and what it may run on
 * its own. Phase 5's acceptance is that forbidden and over-blast actions are rejected here.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class PolicyEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final Map<String, Boolean> ALL_PASS = Map.of("no_deploy_freeze", true, "other_replicas_healthy", true,
            "within_replica_bounds", true, "previous_version_exists", true, "flag_exists", true, "database_healthy", true,
            "lag_below_retention", true, "within_pool_bounds", true, "standby_healthy", true);

    private ActionCatalog catalog;
    private DependencyGraph graph;

    /**
     * Loads the real catalog and dependency graph that ship with the engine.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void setUp() {
        catalog = ActionCatalog.load("catalog-v1.yaml");
        graph = DependencyGraph.load("dependency-graph.yaml");
    }

    /**
     * Builds an engine in a mode, with the kill switch off.
     *
     * @param mode suggest or autonomous
     * @return the engine
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private PolicyEngine engine(PolicyEngine.Mode mode) {
        return new PolicyEngine(catalog, graph, mode, false);
    }

    /**
     * Builds a proposal with every precondition passing and no history.
     *
     * @param action the action
     * @param target the service
     * @return the proposal
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static Proposal proposal(String action, String target) {
        return new Proposal("p-1", action, target, ALL_PASS, List.of(), NOW);
    }

    /**
     * An action that is not in the catalog is rejected, whatever it is called.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsAnActionOutsideTheCatalog() {
        for (String action : List.of("delete_database", "run_shell", "restart_service; rm -rf /", "")) {
            Decision decision = engine(PolicyEngine.Mode.AUTONOMOUS).decide(proposal(action, "inventory"));
            assertThat(decision.verdict()).as(action).isEqualTo(Decision.Verdict.REJECTED);
            assertThat(decision.reasons()).anyMatch(reason -> reason.contains("not in the catalog"));
        }
    }

    /**
     * A known action aimed at a service it does not allow, such as failing over checkout, is rejected.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsATargetTheActionDoesNotAllow() {
        Decision decision = engine(PolicyEngine.Mode.SUGGEST).decide(proposal("failover", "checkout"));
        assertThat(decision.verdict()).isEqualTo(Decision.Verdict.REJECTED);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("cannot target checkout"));
    }

    /**
     * Restarting catalog would touch catalog, cart, checkout and the gateway: four services, over the limit of three.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsAnActionOverItsBlastRadius() {
        assertThat(graph.blastRadius("catalog")).containsExactlyInAnyOrder("catalog", "cart", "checkout", "gateway");
        Decision decision = engine(PolicyEngine.Mode.SUGGEST).decide(proposal("restart_service", "catalog"));
        assertThat(decision.verdict()).isEqualTo(Decision.Verdict.REJECTED);
        assertThat(decision.reasons()).anyMatch(reason -> reason.contains("blast radius of 4"));
    }

    /**
     * Restarting inventory touches inventory, checkout and the gateway, within the limit, so it goes to a person.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void sendsAnAllowedActionForApprovalInSuggestMode() {
        Decision decision = engine(PolicyEngine.Mode.SUGGEST).decide(proposal("restart_service", "inventory"));
        assertThat(decision.verdict()).isEqualTo(Decision.Verdict.NEEDS_APPROVAL);
        assertThat(decision.blastRadius()).isEqualTo(Set.of("inventory", "checkout", "gateway"));
        assertThat(decision.approvals()).isEqualTo(1);
    }

    /**
     * In autonomous mode, only a low-risk action whose preconditions all pass runs without a person.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void runsOnlyLowRiskActionsAloneInAutonomousMode() {
        assertThat(engine(PolicyEngine.Mode.AUTONOMOUS).decide(proposal("resume_consumer", "notifier")).verdict())
                .isEqualTo(Decision.Verdict.APPROVED);
        assertThat(engine(PolicyEngine.Mode.AUTONOMOUS).decide(proposal("restart_service", "inventory")).verdict())
                .isEqualTo(Decision.Verdict.NEEDS_APPROVAL);
        Decision failover = engine(PolicyEngine.Mode.AUTONOMOUS).decide(proposal("failover", "payments"));
        assertThat(failover.verdict()).isEqualTo(Decision.Verdict.NEEDS_APPROVAL);
        assertThat(failover.approvals()).isEqualTo(2);
    }

    /**
     * A failing or unknown precondition rejects the action, and names the precondition.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsWhenAPreconditionFailsOrIsUnknown() {
        Proposal frozen = new Proposal("p-2", "restart_service", "inventory", Map.of("no_deploy_freeze", false,
                "other_replicas_healthy", true), List.of(), NOW);
        assertThat(engine(PolicyEngine.Mode.SUGGEST).decide(frozen).reasons()).anyMatch(r -> r.contains("no_deploy_freeze"));
        Proposal unknown = new Proposal("p-3", "restart_service", "inventory", Map.of("no_deploy_freeze", true), List.of(), NOW);
        Decision decision = engine(PolicyEngine.Mode.SUGGEST).decide(unknown);
        assertThat(decision.verdict()).isEqualTo(Decision.Verdict.REJECTED);
        assertThat(decision.reasons()).anyMatch(r -> r.contains("other_replicas_healthy"));
    }

    /**
     * An action already run its maximum number of times in the last hour is rejected; older runs do not count.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsAnActionOverItsHourlyLimit() {
        List<Instant> recent = List.of(NOW.minusSeconds(600), NOW.minusSeconds(1200), NOW.minusSeconds(1800));
        Proposal busy = new Proposal("p-4", "restart_service", "inventory", ALL_PASS, recent, NOW);
        assertThat(engine(PolicyEngine.Mode.SUGGEST).decide(busy).reasons()).anyMatch(r -> r.contains("3 times in the last hour"));
        Proposal old = new Proposal("p-5", "restart_service", "inventory", ALL_PASS,
                List.of(NOW.minusSeconds(3700), NOW.minusSeconds(7200), NOW.minusSeconds(9000)), NOW);
        assertThat(engine(PolicyEngine.Mode.SUGGEST).decide(old).verdict()).isEqualTo(Decision.Verdict.NEEDS_APPROVAL);
    }

    /**
     * With the kill switch on, nothing is approved or even sent for approval.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsEverythingWhenTheKillSwitchIsOn() {
        Decision decision = new PolicyEngine(catalog, graph, PolicyEngine.Mode.AUTONOMOUS, true)
                .decide(proposal("resume_consumer", "notifier"));
        assertThat(decision.verdict()).isEqualTo(Decision.Verdict.REJECTED);
        assertThat(decision.reasons()).anyMatch(r -> r.contains("kill switch"));
    }

    /**
     * Every action in the catalog can run on at least one of its targets, has a verification query and a
     * timeout, so the catalog holds no dead entry.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void everyCatalogActionIsUsableAndVerifiable() {
        assertThat(catalog.actions()).hasSize(9);
        for (ActionCatalog.Action action : catalog.actions()) {
            assertThat(action.targets()).as(action.name()).anyMatch(t -> graph.blastRadius(t).size() <= action.maxBlastRadius());
            assertThat(action.verification().query()).as(action.name()).isNotBlank();
            assertThat(action.verification().timeoutSeconds()).as(action.name()).isPositive();
        }
    }
}
