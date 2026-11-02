package io.github.rnachoc.guardia.policy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GuardiAI's own metrics, in Prometheus's text format, counted from the audit log. The log is the record of
 * what happened, so the metrics cannot disagree with it. Every known verdict and outcome is reported from the
 * start, at zero if need be, so rates and increases work before the first incident.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class MetricsController {

    private static final MediaType PROMETHEUS_TEXT = MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8");
    private static final Map<String, List<String>> KNOWN = Map.of(
            "decision", List.of("APPROVED", "NEEDS_APPROVAL", "REJECTED"),
            "execution", List.of("ran", "refused", "dry_run_failed"),
            "verification", List.of("verified", "not_confirmed"));

    private final AuditLog audit;

    /**
     * Creates the controller.
     *
     * @param audit the audit log
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public MetricsController(AuditLog audit) {
        this.audit = audit;
    }

    /**
     * Renders the metrics.
     *
     * @return the exposition text
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/metrics")
    public ResponseEntity<String> metrics() {
        List<Map<String, Object>> entries = audit.latest(Integer.MAX_VALUE);
        Map<String, Map<String, Long>> byOutcome = new TreeMap<>();
        KNOWN.forEach((kind, values) -> values.forEach(v -> byOutcome.computeIfAbsent(kind, k -> new TreeMap<>()).put(v, 0L)));
        Map<String, Long> byKind = new TreeMap<>();
        for (Map<String, Object> entry : entries) {
            String kind = String.valueOf(entry.get("kind"));
            byKind.merge(kind, 1L, Long::sum);
            if (KNOWN.containsKey(kind) && entry.get("data") instanceof Map<?, ?> data) {
                Object label = data.get("decision".equals(kind) ? "verdict" : "outcome");
                byOutcome.get(kind).merge(String.valueOf(label), 1L, Long::sum);
            }
        }
        StringBuilder out = new StringBuilder();
        counter(out, "guardia_proposals_total", "Proposals the policy engine judged.", byKind.getOrDefault("proposal", 0L));
        labelled(out, "guardia_decisions_total", "The engine's decisions, by verdict. REJECTED counts unsafe proposals.", "verdict", byOutcome.get("decision"));
        counter(out, "guardia_approvals_total", "Approvals given by people.", byKind.getOrDefault("approval", 0L));
        labelled(out, "guardia_executions_total", "Execution attempts, by outcome.", "outcome", byOutcome.get("execution"));
        labelled(out, "guardia_verifications_total", "Recovery checks after an action, by outcome.", "outcome", byOutcome.get("verification"));
        counter(out, "guardia_reverts_total", "Actions reverted because recovery was not confirmed.", byKind.getOrDefault("revert", 0L));
        counter(out, "guardia_escalations_total", "Times a person was called because a remediation failed.", byKind.getOrDefault("escalation", 0L));
        gauge(out, "guardia_audit_entries", "Entries in the audit log.", entries.size());
        gauge(out, "guardia_audit_chain_intact", "1 when every audit entry is hash-linked to the one before it, 0 otherwise.", audit.verify().isEmpty() ? 1 : 0);
        return ResponseEntity.ok().contentType(PROMETHEUS_TEXT).body(out.toString());
    }

    /**
     * Writes an unlabelled counter.
     *
     * @param out where to write
     * @param name the metric's name
     * @param help what it counts
     * @param value its value
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static void counter(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" counter\n")
                .append(name).append(' ').append(value).append('\n');
    }

    /**
     * Writes a counter with one label.
     *
     * @param out where to write
     * @param name the metric's name
     * @param help what it counts
     * @param label the label's name
     * @param values the count for each label value
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static void labelled(StringBuilder out, String name, String help, String label, Map<String, Long> values) {
        out.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" counter\n");
        values.forEach((value, count) -> out.append(name).append('{').append(label).append("=\"")
                .append(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append("\"} ").append(count).append('\n'));
    }

    /**
     * Writes a gauge.
     *
     * @param out where to write
     * @param name the metric's name
     * @param help what it measures
     * @param value its value
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static void gauge(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(value).append('\n');
    }
}
