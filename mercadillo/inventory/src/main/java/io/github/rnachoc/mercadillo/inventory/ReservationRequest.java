package io.github.rnachoc.mercadillo.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A request to hold stock for one order.
 *
 * @param orderId the order the stock is held for; asking twice for the same order holds it once
 * @param items what to hold
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record ReservationRequest(@NotBlank String orderId, @NotEmpty @Size(max = 50) List<@Valid Item> items) {

    /**
     * One line of the reservation.
     *
     * @param sku the product
     * @param quantity how many units, from 1 to 100
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Item(@NotBlank String sku, @Min(1) @Max(100) int quantity) {
    }
}
