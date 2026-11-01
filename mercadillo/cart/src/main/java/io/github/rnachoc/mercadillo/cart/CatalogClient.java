package io.github.rnachoc.mercadillo.cart;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks the catalog whether a product exists.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CatalogClient {

    private final RestClient catalog;

    /**
     * Creates the client. Timeouts come from {@code spring.http.client.*}.
     *
     * @param builder Spring's builder, configured with the service's timeouts and instrumented for tracing
     * @param catalogUrl the catalog's base URL
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CatalogClient(RestClient.Builder builder, @Value("${mercadillo.catalog-url}") String catalogUrl) {
        this.catalog = builder.baseUrl(catalogUrl).build();
    }

    /**
     * Checks a product.
     *
     * @param sku the product
     * @return {@code true} when the catalog knows the product, {@code false} when it answers 404
     * @throws RestClientException when the catalog fails in any other way
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public boolean exists(String sku) {
        return catalog.get()
                .uri("/products/{sku}", sku)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return true;
                    }
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return false;
                    }
                    throw new RestClientException(
                            "catalog answered " + response.getStatusCode().value());
                });
    }
}
