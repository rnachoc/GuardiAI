package io.github.rnachoc.mercadillo.gateway;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Forwards a request to a service and hands its answer back unchanged: status and JSON body. Only JSON
 * crosses the gateway, and no header from the shopper is passed on.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class Forwarder {

    private final RestClient http;

    /**
     * Creates the forwarder.
     *
     * @param builder Spring's builder, configured with the service's timeouts and instrumented for tracing
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Forwarder(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /**
     * Forwards one request.
     *
     * @param method the HTTP method
     * @param target the full URL of the service's endpoint
     * @param body the JSON body, or {@code null} for none
     * @return the service's status and body; 502 when the service cannot be reached in time
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public ResponseEntity<String> forward(HttpMethod method, URI target, String body) {
        RestClient.RequestBodySpec request = http.method(method).uri(target).accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        try {
            return request.exchange((sent, response) -> {
                String answer = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                ResponseEntity.BodyBuilder reply = ResponseEntity.status(response.getStatusCode());
                return answer.isEmpty() ? reply.build() : reply.contentType(MediaType.APPLICATION_JSON).body(answer);
            });
        } catch (ResourceAccessException unreachable) {
            return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"a service did not answer in time\"}");
        }
    }
}
