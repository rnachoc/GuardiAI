package io.github.rnachoc.guardia.policy;

import java.util.Map;

/**
 * Sends each action, and each revert, to the runner that implements it. An action no runner implements
 * fails its dry run, so the executor never believes it ran.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class RoutingRunner implements Executor.Runner {

    private final Map<String, Executor.Runner> actions;
    private final Map<String, Executor.Runner> reverts;

    /**
     * Creates the router.
     *
     * @param actions the runner for each action
     * @param reverts the runner for each revert
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public RoutingRunner(Map<String, Executor.Runner> actions, Map<String, Executor.Runner> reverts) {
        this.actions = Map.copyOf(actions);
        this.reverts = Map.copyOf(reverts);
    }

    /**
     * Asks the action's runner for a dry run.
     *
     * @param action the action
     * @param target the target
     * @return {@code true} when a runner implements it and could run it
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public boolean dryRun(String action, String target) {
        Executor.Runner runner = actions.get(action);
        return runner != null && runner.dryRun(action, target);
    }

    /**
     * Runs the action through its runner.
     *
     * @param action the action
     * @param target the target
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void run(String action, String target) {
        Executor.Runner runner = actions.get(action);
        if (runner == null) {
            throw new IllegalArgumentException(action + " is not implemented by any runner");
        }
        runner.run(action, target);
    }

    /**
     * Runs the revert through its runner.
     *
     * @param revertAction the revert
     * @param target the target
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void revert(String revertAction, String target) {
        Executor.Runner runner = reverts.get(revertAction);
        if (runner == null) {
            throw new IllegalArgumentException(revertAction + " is not implemented by any runner");
        }
        runner.revert(revertAction, target);
    }
}
