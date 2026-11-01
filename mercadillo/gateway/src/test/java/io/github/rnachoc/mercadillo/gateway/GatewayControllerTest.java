package io.github.rnachoc.mercadillo.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks that the gateway builds each service URL safely.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@WebMvcTest(GatewayController.class)
class GatewayControllerTest {

    @Autowired
    private MockMvc http;

    @MockitoBean
    private Forwarder forwarder;

    /**
     * A path variable that tries to climb out of its route is encoded, so it stays one path segment.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void encodesPathVariables() throws Exception {
        when(forwarder.forward(any(), any(), any())).thenReturn(ResponseEntity.ok("{}"));

        http.perform(get("/api/products/{sku}", "..%2Factuator")).andExpect(status().isOk());

        verify(forwarder).forward(eq(HttpMethod.GET), eq(URI.create("http://catalog/products/..%252Factuator")), isNull());
    }

    /**
     * Where the services live during the test.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @TestConfiguration
    static class TestRoutes {

        /**
         * Provides fixed service addresses.
         *
         * @return the routes
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Bean
        Routes routes() {
            return new Routes("http://catalog", "http://cart", "http://checkout");
        }
    }
}
