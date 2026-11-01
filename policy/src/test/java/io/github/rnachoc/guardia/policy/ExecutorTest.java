package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Written before the executor: it runs only what was allowed, once per idempotency key, dry run first; it
 * verifies recovery, reverts and escalates when recovery is not confirmed; and it audits every step.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class ExecutorTest {

    @TempDir
    Path folder;

    private AuditLog audit;
    private FakeRunner runner;
    private final List<String> escalations = new ArrayList<>();

    /**
     * Sets up a fresh audit log and a runner that records what it is asked to do.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void setUp() {
        audit = new AuditLog(folder.resolve("audit.jsonl"));
        runner = new FakeRunner();
    }

    /**
     * Builds an executor whose verification answers with the given results in turn.
     *
     * @param checks what each verification poll returns
     * @return the executor
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Executor executor(Boolean... checks) {
        List<Boolean> answers = new ArrayList<>(List.of(checks));
        return new Executor(runner, query -> answers.isEmpty() ? false : answers.remove(0), audit, escalations::add, 3);
    }

    /**
     * Builds a decision for restarting inventory.
     *
     * @param verdict the engine's verdict
     * @param approvals how many people must approve
     * @return the decision
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static Decision decision(Decision.Verdict verdict, int approvals) {
        return new Decision("p-1", verdict, List.of(), Set.of("inventory", "checkout", "gateway"), approvals);
    }

    private static final Executor.Request RESTART = new Executor.Request("p-1", "restart_service", "inventory",
            "sum(rate(http_server_request_duration_seconds_count{service_name=\"inventory\"}[1m])) > 0", "none");

    /**
     * A rejected decision never runs, and neither does one still waiting for its approvals.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void runsNothingThatWasNotAllowed() {
        assertThat(executor(true).execute(RESTART, decision(Decision.Verdict.REJECTED, 1), 1).outcome()).isEqualTo(Executor.Outcome.REFUSED);
        assertThat(executor(true).execute(RESTART, decision(Decision.Verdict.NEEDS_APPROVAL, 1), 0).outcome()).isEqualTo(Executor.Outcome.REFUSED);
        assertThat(runner.calls).isEmpty();
    }

    /**
     * An approved action runs after a dry run, is verified, and its steps are audited in order.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void runsAfterADryRunAndVerifies() {
        Executor.Result result = executor(false, true).execute(RESTART, decision(Decision.Verdict.NEEDS_APPROVAL, 1), 1);
        assertThat(result.outcome()).isEqualTo(Executor.Outcome.VERIFIED);
        assertThat(runner.calls).containsExactly("dry-run restart_service inventory", "run restart_service inventory");
        assertThat(audit.verify()).isEmpty();
    }

    /**
     * The same proposal executed twice runs once: the second call finds its idempotency key already claimed.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void runsEachProposalOnce() {
        Executor executor = executor(true, true);
        executor.execute(RESTART, decision(Decision.Verdict.APPROVED, 0), 0);
        assertThat(executor.execute(RESTART, decision(Decision.Verdict.APPROVED, 0), 0).outcome()).isEqualTo(Executor.Outcome.ALREADY_DONE);
        assertThat(runner.calls).containsExactly("dry-run restart_service inventory", "run restart_service inventory");
    }

    /**
     * A failing dry run stops the action before anything changes.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void stopsWhenTheDryRunFails() {
        runner.dryRunFails = true;
        assertThat(executor(true).execute(RESTART, decision(Decision.Verdict.APPROVED, 0), 0).outcome()).isEqualTo(Executor.Outcome.DRY_RUN_FAILED);
        assertThat(runner.calls).containsExactly("dry-run restart_service inventory");
    }

    /**
     * When recovery is not confirmed within the polls, the action is reverted and a person is called.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void revertsAndEscalatesWhenRecoveryIsNotConfirmed() {
        Executor.Request scale = new Executor.Request("p-2", "scale_deployment", "checkout", "up > 0", "scale_back");
        Executor.Result result = executor(false, false, false).execute(scale, decision(Decision.Verdict.APPROVED, 0), 0);
        assertThat(result.outcome()).isEqualTo(Executor.Outcome.REVERTED);
        assertThat(runner.calls).containsExactly("dry-run scale_deployment checkout", "run scale_deployment checkout",
                "revert scale_back checkout");
        assertThat(escalations).singleElement().asString().contains("p-2");
        assertThat(audit.verify()).isEmpty();
    }

    /**
     * An action with nothing to revert to is escalated without a revert.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void escalatesWithoutRevertWhenThereIsNothingToRevert() {
        Executor.Result result = executor(false, false, false).execute(RESTART, decision(Decision.Verdict.APPROVED, 0), 0);
        assertThat(result.outcome()).isEqualTo(Executor.Outcome.ESCALATED);
        assertThat(runner.calls).noneMatch(call -> call.startsWith("revert"));
        assertThat(escalations).hasSize(1);
    }

    /**
     * Records what the executor asks it to do.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    static final class FakeRunner implements Executor.Runner {
        final List<String> calls = new ArrayList<>();
        boolean dryRunFails;

        /**
         * Records a dry run, and fails it when asked to.
         *
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Override
        public boolean dryRun(String action, String target) {
            calls.add("dry-run " + action + " " + target);
            return !dryRunFails;
        }

        /**
         * Records a run.
         *
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Override
        public void run(String action, String target) {
            calls.add("run " + action + " " + target);
        }

        /**
         * Records a revert.
         *
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Override
        public void revert(String revertAction, String target) {
            calls.add("revert " + revertAction + " " + target);
        }
    }
}
