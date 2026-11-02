package io.github.rnachoc.guardia.policy;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Judges proposals over HTTP, records people's approvals, and executes approved proposals, writing every
 * step to the audit log. Only a proposal the engine judged here can be approved or executed.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class ProposalController {

    private final PolicyEngine engine;
    private final AuditLog audit;
    private final Supplier<Map<String, Boolean>> facts;
    private final ActionCatalog catalog;
    private final Executor executor;
    private final Map<String, Judged> judged = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    /**
     * Creates the controller.
     *
     * @param engine the policy engine
     * @param audit the audit log
     * @param facts the current facts, gathered by code
     * @param catalog the action catalog, for each action's verification and revert
     * @param executor what runs approved proposals
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public ProposalController(PolicyEngine engine, AuditLog audit, Supplier<Map<String, Boolean>> facts,
                              ActionCatalog catalog, Executor executor) {
        this.engine = engine;
        this.audit = audit;
        this.facts = facts;
        this.catalog = catalog;
        this.executor = executor;
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
        judged.put(request.id(), new Judged(request, decision, java.util.Collections.synchronizedSet(new LinkedHashSet<>())));
        return decision;
    }

    /**
     * Records one person's approval. The same person approving twice counts once.
     *
     * @param id the proposal
     * @param approval who approves
     * @return how many distinct people have approved, and how many are needed
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/proposals/{id}/approvals")
    public Map<String, Object> approve(@PathVariable String id, @Valid @RequestBody Approval approval) {
        Judged found = find(id);
        if (found.approvers().add(approval.by())) {
            audit.append("approval", Map.of("proposal", id, "by", approval.by()));
        }
        return Map.of("proposal", id, "approvals", found.approvers().size(), "needed", found.decision().approvals());
    }

    /**
     * Executes a judged proposal through the executor, which refuses unless it is allowed.
     *
     * @param id the proposal
     * @return the outcome
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/proposals/{id}/execute")
    public Executor.Result execute(@PathVariable String id) {
        Judged found = find(id);
        ActionCatalog.Action action = catalog.find(found.request().action()).orElse(null);
        if (action == null) {
            audit.append("execution", Map.of("proposal", id, "outcome", "refused", "verdict", found.decision().verdict().name()));
            return new Executor.Result(Executor.Outcome.REFUSED);
        }
        Executor.Request request = new Executor.Request(id, action.name(), found.request().target(),
                action.verification().query().replace("$target", found.request().target()),
                action.verification().expect(), action.autoRevert());
        return executor.execute(request, found.decision(), found.approvers().size());
    }

    /**
     * Finds a proposal judged here, or answers 404.
     *
     * @param id the proposal
     * @return what was judged
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Judged find(String id) {
        Judged found = judged.get(id);
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "proposal " + id + " was not judged here");
        }
        return found;
    }

    /**
     * A proposal as judged, with the people who approved it.
     *
     * @param request the proposal
     * @param decision the engine's decision
     * @param approvers the distinct people who approved it
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    record Judged(ProposalRequest request, Decision decision, Set<String> approvers) {
    }

    /**
     * One person's approval.
     *
     * @param by who approves
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Approval(@NotBlank @Pattern(regexp = "[a-z][a-z0-9._-]{1,40}") String by) {
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
