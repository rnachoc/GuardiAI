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
    PolicyEngine policyEngine(@Value("${guardia.policy.mode:SUGGEST}") PolicyEngine.Mode mode,
                              @Value("${guardia.policy.kill-switch:false}") boolean killSwitch) {
        return new PolicyEngine(ActionCatalog.load("catalog-v1.yaml"), DependencyGraph.load("dependency-graph.yaml"), mode, killSwitch);
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
