package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls a person through a Slack-style incoming webhook: a POST of {@code {"text": …}}. Every escalation is
 * also logged, and a webhook that is down or refuses is logged and never thrown, because the executor has
 * already reverted by the time it escalates and must not fail on the way out.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class WebhookEscalation implements Consumer<String> {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookEscalation.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Creates the escalation.
     *
     * @param url the webhook's URL
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public WebhookEscalation(String url) {
        this.url = URI.create(url);
    }

    /**
     * Logs the message and posts it to the webhook.
     *
     * @param message what the person needs to know
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void accept(String message) {
        LOG.warn("ESCALATION: {}", message);
        try {
            String body = JSON.writeValueAsString(Map.of("text", "GuardiAI needs a person: " + message));
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                LOG.error("the escalation webhook answered {}; the escalation is only in this log", response.statusCode());
            }
        } catch (java.io.IOException e) {
            LOG.error("the escalation webhook could not be reached ({}); the escalation is only in this log", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("interrupted while calling the escalation webhook; the escalation is only in this log");
        }
    }
}
