package io.github.rnachoc.guardia.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Written before the endpoints: a judged proposal runs only once enough distinct people approve it, runs
 * once, and an unknown proposal cannot be approved or run.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExecutionControllerTest {

    private static final Path FOLDER = folder();
    static final List<String> RUNS = new ArrayList<>();

    @Autowired
    private MockMvc http;

    /**
     * Creates a scratch folder with facts under which restarting inventory passes every check.
     *
     * @return the folder
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static Path folder() {
        try {
            Path path = Files.createTempDirectory("execution-");
            Files.writeString(path.resolve("facts.json"), "{\"no_deploy_freeze\":true,\"other_replicas_healthy\":true}");
            return path;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Points the service at the scratch files.
     *
     * @param registry Spring's registry of test properties
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) {
        registry.add("guardia.audit.file", () -> FOLDER.resolve("audit.jsonl").toString());
        registry.add("guardia.facts.file", () -> FOLDER.resolve("facts.json").toString());
    }

    /**
     * Replaces the real runner and verifier with ones that record runs and always see recovery.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @TestConfiguration
    static class Fakes {

        /**
         * A runner that records what it runs.
         *
         * @return the runner
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Bean
        @Primary
        Executor.Runner fakeRunner() {
            return new Executor.Runner() {
                /**
                 * Allows every dry run.
                 *
                 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
                 */
                @Override
                public boolean dryRun(String action, String target) {
                    return true;
                }

                /**
                 * Records a run.
                 *
                 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
                 */
                @Override
                public void run(String action, String target) {
                    RUNS.add(action + " " + target);
                }

                /**
                 * Records a revert.
                 *
                 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
                 */
                @Override
                public void revert(String revertAction, String target) {
                    RUNS.add("revert " + target);
                }
            };
        }

        /**
         * A verifier that always sees recovery.
         *
         * @return the verifier
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Bean
        @Primary
        Executor.Verifier fakeVerifier() {
            return (query, expect) -> true;
        }
    }

    /**
     * Posts a JSON body to a path.
     *
     * @param path the path
     * @param body the JSON body
     * @return the result
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private org.springframework.test.web.servlet.ResultActions send(String path, String body) throws Exception {
        return http.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * Without approval nothing runs; with one person's approval, given twice, it runs once and is verified;
     * running it again changes nothing.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void runsOnlyOnceApprovedAndOnlyOnce() throws Exception {
        String id = "prop-11111111-2222-3333-4444-555555555555";
        send("/proposals", "{\"id\":\"" + id + "\",\"action\":\"restart_service\",\"target\":\"inventory\"}")
                .andExpect(jsonPath("$.verdict").value("NEEDS_APPROVAL"));
        send("/proposals/" + id + "/execute", "{}").andExpect(jsonPath("$.outcome").value("REFUSED"));
        send("/proposals/" + id + "/approvals", "{\"by\":\"ana\"}").andExpect(jsonPath("$.approvals").value(1));
        send("/proposals/" + id + "/approvals", "{\"by\":\"ana\"}").andExpect(jsonPath("$.approvals").value(1));
        send("/proposals/" + id + "/execute", "{}").andExpect(jsonPath("$.outcome").value("VERIFIED"));
        send("/proposals/" + id + "/execute", "{}").andExpect(jsonPath("$.outcome").value("ALREADY_DONE"));
        org.assertj.core.api.Assertions.assertThat(RUNS).containsExactly("restart_service inventory");
    }

    /**
     * A proposal the engine never judged cannot be approved or run.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesUnknownProposals() throws Exception {
        String id = "prop-99999999-9999-9999-9999-999999999999";
        send("/proposals/" + id + "/approvals", "{\"by\":\"ana\"}").andExpect(status().isNotFound());
        send("/proposals/" + id + "/execute", "{}").andExpect(status().isNotFound());
    }
}
