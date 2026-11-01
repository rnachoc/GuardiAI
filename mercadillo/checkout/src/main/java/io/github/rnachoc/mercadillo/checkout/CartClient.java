package io.github.rnachoc.mercadillo.checkout;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads and clears carts in the cart service.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CartClient {

    private final RestClient carts;

    /**
     * Creates the client.
     *
     * @param carts the HTTP client bound to the cart service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CartClient(@Qualifier("cartRestClient") RestClient carts) {
        this.carts = carts;
    }

    /**
     * Reads a cart.
     *
     * @param cartId the cart
     * @return the cart's lines, or empty when the cart does not exist
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Optional<List<Line>> lines(String cartId) {
        return carts.get()
                .uri("/carts/{id}", cartId)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return Optional.<List<Line>>empty();
                    }
                    Downstream.requireSuccess("cart", response.getStatusCode());
                    CartView cart = response.bodyTo(CartView.class);
                    return Optional.of(cart == null ? List.<Line>of() : cart.items());
                });
    }

    /**
     * Deletes a cart once its order is placed.
     *
     * @param cartId the cart
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public void delete(String cartId) {
        carts.delete().uri("/carts/{id}", cartId).retrieve().toBodilessEntity();
    }

    /**
     * The part of a cart that checkout reads.
     *
     * @param id the cart's identifier
     * @param items the lines
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    record CartView(String id, List<Line> items) {
    }
}
