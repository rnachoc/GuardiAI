package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Written before the webhook escalation: it posts a Slack-style message to a webhook when the executor needs
 * a person. A webhook that is down or refuses must never break the executor, which has already reverted.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class WebhookEscalationTest {

    private HttpServer webhook;
    private int status = 200;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> types = new ArrayList<>();

    /**
     * Starts a stand-in webhook that records what it receives and answers with {@link #status}.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void start() throws IOException {
        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            types.add(exchange.getRequestMethod() + " " + exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        webhook.start();
    }

    /**
     * Stops the stand-in.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @AfterEach
    void stop() {
        webhook.stop(0);
    }

    /**
     * Returns the stand-in's URL.
     *
     * @return the URL
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private String url() {
        return "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook";
    }

    /**
     * The message arrives as JSON with a text field, so a Slack-compatible webhook shows it.
     *
     * @throws IOException if the body is not JSON
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void postsTheMessageAsSlackStyleJson() throws IOException {
        new WebhookEscalation(url()).accept("Proposal p-1 did not recover the system and was reverted with none");
        assertThat(types).containsExactly("POST application/json");
        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertThat(body.get("text").asText()).isEqualTo("GuardiAI needs a person: Proposal p-1 did not recover the system and was reverted with none");
    }

    /**
     * A webhook that answers with an error does not throw.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void aRefusingWebhookDoesNotBreakTheExecutor() {
        status = 500;
        assertThatCode(() -> new WebhookEscalation(url()).accept("x")).doesNotThrowAnyException();
        assertThat(bodies).hasSize(1);
    }

    /**
     * A webhook that cannot be reached does not throw either.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void anUnreachableWebhookDoesNotBreakTheExecutor() {
        String dead = url();
        webhook.stop(0);
        assertThatCode(() -> new WebhookEscalation(dead).accept("x")).doesNotThrowAnyException();
    }
}
