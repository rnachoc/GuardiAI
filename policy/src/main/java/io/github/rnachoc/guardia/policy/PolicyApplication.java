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
     * @return the runner
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Executor.Runner runner(@Value("${guardia.runner:none}") String kind,
                           @Value("${guardia.docker.socket:/var/run/docker.sock}") Path socket,
                           @Value("${guardia.docker.project:guardia}") String project) {
        if ("docker".equals(kind)) {
            return new DockerRunner(socket, project);
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
     * @return the executor
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Executor executor(Executor.Runner runner, Executor.Verifier verifier, AuditLog audit,
                      @Value("${guardia.executor.polls:12}") int polls,
                      @Value("${guardia.executor.poll-interval-ms:10000}") long interval) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Executor.class);
        return new Executor(runner, verifier, audit, message -> log.warn("ESCALATION: {}", message), polls, interval);
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
     * Reads the facts preconditions are judged on, from a JSON file that code keeps up to date. A missing or
     * unreadable file yields no facts, so every precondition is unknown and every proposal is rejected.
     *
     * @param file the facts file
     * @return a supplier that reads the file on each call
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Supplier<Map<String, Boolean>> facts(@Value("${guardia.facts.file:/data/facts.json}") Path file) {
        ObjectMapper json = new ObjectMapper();
        return () -> {
            try {
                return Files.exists(file) ? json.readValue(file.toFile(), new TypeReference<Map<String, Boolean>>() { }) : Map.of();
            } catch (IOException unreadable) {
                return Map.of();
            }
        };
    }
}
