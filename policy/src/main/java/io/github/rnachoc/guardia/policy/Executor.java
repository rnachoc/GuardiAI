package io.github.rnachoc.guardia.policy;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Executes what the policy engine allowed, and nothing else. Each proposal runs at most once: its ID is the
 * idempotency key, claimed in the audit log before anything happens, so the claim survives a restart. The
 * action is dry-run first, then run, then verified by polling its query. When recovery is not confirmed,
 * the action is reverted if it can be, and a person is called. Every step is audited.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class Executor {

    private final Runner runner;
    private final Verifier verifier;
    private final AuditLog audit;
    private final Consumer<String> escalate;
    private final int polls;
    private final long pollIntervalMillis;
    private final long settleMillis;

    /**
     * Creates the executor.
     *
     * @param runner what actually acts on the system
     * @param verifier what evaluates a verification query
     * @param audit the audit log
     * @param escalate how a person is called
     * @param polls how many times verification is checked before giving up
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Executor(Runner runner, Verifier verifier, AuditLog audit, Consumer<String> escalate, int polls) {
        this(runner, verifier, audit, escalate, polls, 0, 0);
    }

    /**
     * Creates the executor with a settle period after running and a wait between verification checks. The
     * settle period must cover the longest range in a verification query, so that a rate over [1m] holds
     * only data from after the action and a dead service's old samples cannot pass for recovery.
     *
     * @param runner what actually acts on the system
     * @param verifier what evaluates a verification query
     * @param audit the audit log
     * @param escalate how a person is called
     * @param polls how many times verification is checked before giving up
     * @param pollIntervalMillis how long to wait before each check
     * @param settleMillis how long to wait after running before the first check
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Executor(Runner runner, Verifier verifier, AuditLog audit, Consumer<String> escalate, int polls,
                    long pollIntervalMillis, long settleMillis) {
        this.runner = runner;
        this.verifier = verifier;
        this.audit = audit;
        this.escalate = escalate;
        this.polls = polls;
        this.pollIntervalMillis = pollIntervalMillis;
        this.settleMillis = settleMillis;
    }

    /**
     * Executes one decided proposal.
     *
     * @param request what to run, how to verify it and how to revert it
     * @param decision the engine's decision for it
     * @param approvalsGiven how many people approved it
     * @return what happened
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public synchronized Result execute(Request request, Decision decision, int approvalsGiven) {
        boolean allowed = decision.verdict() == Decision.Verdict.APPROVED
                || (decision.verdict() == Decision.Verdict.NEEDS_APPROVAL && approvalsGiven >= decision.approvals());
        if (!allowed) {
            audit.append("execution", Map.of("proposal", request.proposalId(), "outcome", "refused", "verdict", decision.verdict().name()));
            return new Result(Outcome.REFUSED);
        }
        if (audit.contains("claim", "proposal", request.proposalId())) {
            return new Result(Outcome.ALREADY_DONE);
        }
        audit.append("claim", Map.of("proposal", request.proposalId()));
        if (!runner.dryRun(request.action(), request.target())) {
            audit.append("execution", Map.of("proposal", request.proposalId(), "outcome", "dry_run_failed"));
            return new Result(Outcome.DRY_RUN_FAILED);
        }
        runner.run(request.action(), request.target());
        audit.append("execution", Map.of("proposal", request.proposalId(), "outcome", "ran", "action", request.action(), "target", request.target()));
        if (settleMillis > 0) {
            try {
                Thread.sleep(settleMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        for (int poll = 1; poll <= polls; poll++) {
            if (pollIntervalMillis > 0) {
                try {
                    Thread.sleep(pollIntervalMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (verifier.holds(request.verificationQuery(), request.expect())) {
                audit.append("verification", Map.of("proposal", request.proposalId(), "outcome", "verified", "poll", poll));
                return new Result(Outcome.VERIFIED);
            }
        }
        audit.append("verification", Map.of("proposal", request.proposalId(), "outcome", "not_confirmed", "polls", polls));
        if (!"none".equals(request.autoRevert())) {
            runner.revert(request.autoRevert(), request.target());
            audit.append("revert", Map.of("proposal", request.proposalId(), "action", request.autoRevert(), "target", request.target()));
            escalate.accept("Proposal " + request.proposalId() + " did not recover the system and was reverted with " + request.autoRevert());
            return new Result(Outcome.REVERTED);
        }
        escalate.accept("Proposal " + request.proposalId() + " did not recover the system, and has nothing to revert to");
        audit.append("escalation", Map.of("proposal", request.proposalId()));
        return new Result(Outcome.ESCALATED);
    }

    /**
     * What to execute.
     *
     * @param proposalId the proposal, which is also the idempotency key
     * @param action the action
     * @param target the service
     * @param verificationQuery the query that shows whether the system recovered
     * @param expect the condition its value must meet, such as {@code > 0}
     * @param autoRevert the action that undoes it, or {@code none}
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Request(String proposalId, String action, String target, String verificationQuery, String expect, String autoRevert) {
    }

    /**
     * What happened.
     *
     * @param outcome the outcome
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Result(Outcome outcome) {
    }

    /**
     * The possible outcomes.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public enum Outcome {
        REFUSED,
        ALREADY_DONE,
        DRY_RUN_FAILED,
        VERIFIED,
        REVERTED,
        ESCALATED
    }

    /**
     * Acts on the system. The only implementation with real effects runs in the executor's process.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public interface Runner {

        /**
         * Checks that an action could run, changing nothing.
         *
         * @param action the action
         * @param target the service
         * @return {@code true} when it could run
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        boolean dryRun(String action, String target);

        /**
         * Runs an action.
         *
         * @param action the action
         * @param target the service
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        void run(String action, String target);

        /**
         * Undoes an action.
         *
         * @param revertAction the catalog's auto-revert for it
         * @param target the service
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        void revert(String revertAction, String target);
    }

    /**
     * Evaluates a verification query.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public interface Verifier {

        /**
         * Tells whether the query's value meets the condition now.
         *
         * @param query the query
         * @param expect the condition, such as {@code > 0}
         * @return {@code true} when it holds
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        boolean holds(String query, String expect);
    }
}
