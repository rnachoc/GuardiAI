package io.github.rnachoc.guardia.policy;

import java.util.List;
import java.util.Set;

/**
 * What the engine decided about a proposal, and why.
 *
 * @param proposalId the proposal judged
 * @param verdict rejected, needs approval, or approved to run
 * @param reasons every reason for a rejection, or the basis for the verdict
 * @param blastRadius the services the action could affect
 * @param approvals how many people must approve it
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Decision(String proposalId, Verdict verdict, List<String> reasons, Set<String> blastRadius, int approvals) {

    /**
     * The possible verdicts.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public enum Verdict {
        REJECTED,
        NEEDS_APPROVAL,
        APPROVED
    }
}
