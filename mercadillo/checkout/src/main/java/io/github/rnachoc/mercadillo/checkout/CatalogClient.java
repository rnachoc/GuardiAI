package io.github.rnachoc.mercadillo.checkout;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads prices from the catalog. Checkout prices every order itself and never trusts a price from the cart.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CatalogClient {

    private final RestClient catalog;

    /**
     * Creates the client.
     *
     * @param catalog the HTTP client bound to the catalog
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CatalogClient(@Qualifier("catalogRestClient") RestClient catalog) {
        this.catalog = catalog;
    }

    /**
     * Reads the current price of a product.
     *
     * @param sku the product
     * @return its price
     * @throws CheckoutException with 422 when the product no longer exists, or 502 when the catalog fails
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Price price(String sku) {
        return catalog.get()
                .uri("/products/{sku}", sku)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        throw new CheckoutException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_product",
                                "product " + sku + " is no longer sold");
                    }
                    Downstream.requireSuccess("catalog", response.getStatusCode());
                    return response.bodyTo(Price.class);
                });
    }

    /**
     * The part of a product that checkout reads.
     *
     * @param sku the product
     * @param priceCents the price of one unit, in cents
     * @param currency the ISO 4217 currency code
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Price(String sku, long priceCents, String currency) {
    }
}
