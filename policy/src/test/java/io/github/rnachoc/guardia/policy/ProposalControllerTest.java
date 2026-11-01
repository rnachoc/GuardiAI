package io.github.rnachoc.guardia.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives the policy service over HTTP with the real engine, a real audit log and a facts file.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProposalControllerTest {

    private static final Path FOLDER = createFolder();
    private static final String ID = "prop-0b4c0b7e-1f0e-4d5e-9a7a-2b6f1f2b3c4d";

    @Autowired
    private MockMvc http;

    /**
     * Creates a scratch folder for the audit log and the facts file.
     *
     * @return the folder
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static Path createFolder() {
        try {
            return Files.createTempDirectory("policy-service-");
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
     * Posts a proposal and returns the result actions.
     *
     * @param action the action
     * @param target the service
     * @return the request's result
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private org.springframework.test.web.servlet.ResultActions propose(String action, String target) throws Exception {
        return http.perform(post("/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"" + ID + "\",\"action\":\"" + action + "\",\"target\":\"" + target + "\"}"));
    }

    /**
     * Without a facts file, every precondition is unknown, so even a catalog action is rejected.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void rejectsWhenNoFactsAreKnown() throws Exception {
        Files.deleteIfExists(FOLDER.resolve("facts.json"));
        propose("restart_service", "inventory").andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("REJECTED"))
                .andExpect(jsonPath("$.reasons[0]").value("precondition no_deploy_freeze is unknown"));
    }

    /**
     * With the facts in place, a catalog action within its blast radius goes to a person; one outside the
     * catalog is rejected; and the audit chain stays intact.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void judgesWithFactsFromCodeAndAuditsEverything() throws Exception {
        Files.writeString(FOLDER.resolve("facts.json"), "{\"no_deploy_freeze\":true,\"other_replicas_healthy\":true}");
        propose("restart_service", "inventory").andExpect(jsonPath("$.verdict").value("NEEDS_APPROVAL"));
        propose("delete_database", "postgres").andExpect(jsonPath("$.verdict").value("REJECTED"));
        http.perform(get("/audit/verify")).andExpect(status().isOk()).andExpect(jsonPath("$.problems").isEmpty());
    }

    /**
     * A malformed proposal is refused with 400 before the engine or the audit log sees it.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesMalformedProposals() throws Exception {
        http.perform(post("/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"p-1\",\"action\":\"restart_service\",\"target\":\"inventory; rm -rf /\"}"))
                .andExpect(status().isBadRequest());
    }
}
