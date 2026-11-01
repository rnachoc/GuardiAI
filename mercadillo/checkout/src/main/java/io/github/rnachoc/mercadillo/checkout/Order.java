package io.github.rnachoc.mercadillo.checkout;

import java.time.Instant;
import java.util.List;

/**
 * A placed order: a priced, paid cart.
 *
 * @param id the order's identifier
 * @param cartId the cart it came from
 * @param lines what was bought, at the price paid
 * @param totalCents the total, in cents
 * @param currency the ISO 4217 currency code
 * @param chargeId the payment provider's charge
 * @param placedAt when the order was placed
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Order(String id, String cartId, List<PricedLine> lines, long totalCents, String currency,
                    String chargeId, Instant placedAt) {

    /**
     * Keeps the order's list immutable.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Order {
        lines = List.copyOf(lines);
    }

    /**
     * One product of the order, at the price paid.
     *
     * @param sku the product
     * @param quantity how many units
     * @param unitPriceCents the price of one unit, in cents
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record PricedLine(String sku, int quantity, long unitPriceCents) {
    }
}
