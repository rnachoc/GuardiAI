package io.github.rnachoc.mercadillo.checkout;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Holds and releases stock in the inventory service.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class InventoryClient {

    private final RestClient inventory;

    /**
     * Creates the client.
     *
     * @param inventory the HTTP client bound to the inventory service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public InventoryClient(@Qualifier("inventoryRestClient") RestClient inventory) {
        this.inventory = inventory;
    }

    /**
     * Holds stock for an order.
     *
     * @param orderId the order
     * @param lines what to hold
     * @throws CheckoutException with 409 when a product ran short, or 502 when the inventory fails
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public void reserve(String orderId, List<Line> lines) {
        inventory.post()
                .uri("/reservations")
                .body(Map.of("orderId", orderId, "items", lines))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                        throw new CheckoutException(HttpStatus.CONFLICT, "out_of_stock", "a product ran out of stock");
                    }
                    Downstream.requireSuccess("inventory", response.getStatusCode());
                    return null;
                });
    }

    /**
     * Gives back the stock held for an order that will not be placed.
     *
     * @param orderId the order
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public void release(String orderId) {
        inventory.delete().uri("/reservations/{orderId}", orderId).retrieve().toBodilessEntity();
    }
}
