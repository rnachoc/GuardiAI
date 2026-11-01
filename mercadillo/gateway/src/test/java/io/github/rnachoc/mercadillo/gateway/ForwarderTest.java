package io.github.rnachoc.mercadillo.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Checks that the gateway passes answers back unchanged, failures included.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class ForwarderTest {

    /**
     * A 409 with its body comes back as the same 409 and body, and the request body reaches the service.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void passesStatusAndBodyThrough() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://checkout/checkouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"cartId\":\"c1\"}"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"a product ran out of stock\"}"));

        ResponseEntity<String> answer = new Forwarder(builder)
                .forward(HttpMethod.POST, URI.create("http://checkout/checkouts"), "{\"cartId\":\"c1\"}");

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answer.getBody()).contains("ran out of stock");
        server.verify();
    }

    /**
     * A 204 with no body comes back as a 204 with no body.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void keepsEmptyAnswersEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://cart/carts/c1")).andRespond(withStatus(HttpStatus.NO_CONTENT));

        ResponseEntity<String> answer = new Forwarder(builder)
                .forward(HttpMethod.DELETE, URI.create("http://cart/carts/c1"), null);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(answer.getBody()).isNull();
    }
}
