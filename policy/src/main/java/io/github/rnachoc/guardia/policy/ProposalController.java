package io.github.rnachoc.guardia.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Judges proposals over HTTP, and writes each proposal and its decision to the audit log.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class ProposalController {

    private final PolicyEngine engine;
    private final AuditLog audit;
    private final Supplier<Map<String, Boolean>> facts;
    private final Clock clock = Clock.systemUTC();

    /**
     * Creates the controller.
     *
     * @param engine the policy engine
     * @param audit the audit log
     * @param facts the current facts, gathered by code
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public ProposalController(PolicyEngine engine, AuditLog audit, Supplier<Map<String, Boolean>> facts) {
        this.engine = engine;
        this.audit = audit;
        this.facts = facts;
    }

    /**
     * Judges one proposal. Its facts come from code, never from the request.
     *
     * @param request the proposal, as mcp-actions recorded it
     * @return the decision
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/proposals")
    public Decision judge(@Valid @RequestBody ProposalRequest request) {
        audit.append("proposal", Map.of("id", request.id(), "action", request.action(), "target", request.target()));
        Proposal proposal = new Proposal(request.id(), request.action(), request.target(), facts.get(), List.of(), Instant.now(clock));
        Decision decision = engine.decide(proposal);
        audit.append("decision", Map.of("proposal", request.id(), "verdict", decision.verdict().name(), "reasons", decision.reasons()));
        return decision;
    }

    /**
     * Checks the audit log's chain.
     *
     * @return the problems found, empty when the log is intact
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/audit/verify")
    public Map<String, List<String>> verify() {
        return Map.of("problems", audit.verify());
    }

    /**
     * A proposal to judge.
     *
     * @param id the proposal's identifier, from mcp-actions
     * @param action the action's name
     * @param target the service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record ProposalRequest(@NotBlank @Pattern(regexp = "prop-[0-9a-f-]{36}") String id,
                                  @NotBlank @Pattern(regexp = "[a-z_]{1,40}") String action,
                                  @NotBlank @Pattern(regexp = "[a-z-]{1,40}") String target) {
    }
}
