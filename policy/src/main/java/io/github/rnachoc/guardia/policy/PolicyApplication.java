package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Starts the policy service: the engine, the audit log, and the facts that preconditions are judged on.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootApplication
public class PolicyApplication {

    /**
     * Runs the service.
     *
     * @param args command-line arguments, passed on to Spring Boot
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static void main(String[] args) {
        SpringApplication.run(PolicyApplication.class, args);
    }

    /**
     * Builds the engine from the catalog and dependency graph on the classpath.
     *
     * @param mode suggest, the default, or autonomous
     * @param killSwitch when true, every proposal is rejected
     * @return the engine
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    PolicyEngine policyEngine(ActionCatalog catalog, @Value("${guardia.policy.mode:SUGGEST}") PolicyEngine.Mode mode,
                              @Value("${guardia.policy.kill-switch:false}") boolean killSwitch) {
        return new PolicyEngine(catalog, DependencyGraph.load("dependency-graph.yaml"), mode, killSwitch);
    }

    /**
     * Loads the action catalog on the classpath.
     *
     * @return the catalog
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    ActionCatalog actionCatalog() {
        return ActionCatalog.load("catalog-v1.yaml");
    }

    /**
     * The runner: the Docker API when {@code guardia.runner=docker}; otherwise one whose every dry run fails,
     * so nothing is ever executed by accident.
     *
     * @param kind which runner to use
     * @param socket the Docker daemon's socket
     * @param project the Compose project it may act on
     * @param toxiproxy Toxiproxy's API, through which payments fails over
     * @param flags the flags service
     * @return the runner
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Executor.Runner runner(@Value("${guardia.runner:none}") String kind,
                           @Value("${guardia.docker.socket:/var/run/docker.sock}") Path socket,
                           @Value("${guardia.docker.project:guardia}") String project,
                           @Value("${guardia.toxiproxy.url:http://toxiproxy:8474}") String toxiproxy,
                           @Value("${guardia.flags.url:http://flags:8092}") String flags) {
        if ("docker".equals(kind)) {
            DockerRunner docker = new DockerRunner(socket, project);
            ToxiproxyRunner proxies = new ToxiproxyRunner(toxiproxy);
            FlagsRunner switches = new FlagsRunner(flags);
            return new RoutingRunner(
                    java.util.Map.of("restart_service", docker, "pause_consumer", docker, "resume_consumer", docker,
                            "clear_cache", docker, "scale_deployment", docker, "failover", proxies, "toggle_flag", switches),
                    java.util.Map.of("resume_consumer", docker, "scale_back", docker, "fail_back", proxies, "toggle_back", switches));
        }
        return new Executor.Runner() {
            /**
             * Refuses, since no runner is configured.
             *
             * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
             */
            @Override
            public boolean dryRun(String action, String target) {
                return false;
            }

            /**
             * Never called, since every dry run fails.
             *
             * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
             */
            @Override
            public void run(String action, String target) {
                throw new IllegalStateException("no runner is configured");
            }

            /**
             * Never called, since nothing ran.
             *
             * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
             */
            @Override
            public void revert(String revertAction, String target) {
                throw new IllegalStateException("no runner is configured");
            }
        };
    }

    /**
     * Checks recovery against Prometheus.
     *
     * @param url Prometheus's base URL
     * @return the verifier
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Executor.Verifier verifier(@Value("${guardia.prometheus.url:http://prometheus:9090}") String url) {
        return new PrometheusVerifier(url);
    }

    /**
     * Builds the executor; escalations are written to the log until the webhook mock exists.
     *
     * @param runner what acts on the system
     * @param verifier what checks recovery
     * @param audit the audit log
     * @param polls how many times verification is checked
     * @param interval how long to wait before each check, in milliseconds
     * @param settle how long to wait after running before the first check, in milliseconds
     * @param webhook a Slack-style webhook to call a person through; empty means the log only
     * @return the executor
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Executor executor(Executor.Runner runner, Executor.Verifier verifier, AuditLog audit,
                      @Value("${guardia.executor.polls:12}") int polls,
                      @Value("${guardia.executor.poll-interval-ms:10000}") long interval,
                      @Value("${guardia.executor.settle-ms:70000}") long settle,
                      @Value("${guardia.escalation.webhook-url:}") String webhook) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Executor.class);
        java.util.function.Consumer<String> escalate = webhook.isBlank()
                ? message -> log.warn("ESCALATION: {}", message) : new WebhookEscalation(webhook);
        return new Executor(runner, verifier, audit, escalate, polls, interval, settle);
    }

    /**
     * Opens the audit log.
     *
     * @param file where it is kept
     * @return the log
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    AuditLog auditLog(@Value("${guardia.audit.file:/data/audit.jsonl}") Path file) {
        return new AuditLog(file);
    }

    /**
     * Reads the facts preconditions are judged on: from a JSON file, and, with the Docker runner, from Docker's
     * health status for the facts it can measure (the standby, the database and the flags service), which then
     * override the file. A missing or unreadable file
     * yields no facts, so every precondition is unknown and every proposal is rejected.
     *
     * @param file the facts file
     * @param kind which runner is in use
     * @param socket the Docker daemon's socket
     * @param project the Compose project
     * @return a supplier that reads the facts on each call
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Supplier<Map<String, Boolean>> facts(@Value("${guardia.facts.file:/data/facts.json}") Path file,
                                         @Value("${guardia.runner:none}") String kind,
                                         @Value("${guardia.docker.socket:/var/run/docker.sock}") Path socket,
                                         @Value("${guardia.docker.project:guardia}") String project) {
        ObjectMapper json = new ObjectMapper();
        DockerRunner docker = "docker".equals(kind) ? new DockerRunner(socket, project) : null;
        return () -> {
            Map<String, Boolean> facts;
            try {
                facts = Files.exists(file) ? new java.util.HashMap<>(json.readValue(file.toFile(), new TypeReference<Map<String, Boolean>>() { }))
                        : new java.util.HashMap<>();
            } catch (IOException unreadable) {
                return Map.of();
            }
            if (docker != null && !facts.isEmpty()) {
                facts.put("standby_healthy", docker.healthy("payments-standby"));
                facts.put("database_healthy", docker.healthy("postgres"));
                facts.put("flag_exists", docker.healthy("flags"));
            }
            return Map.copyOf(facts);
        };
    }
}
