package io.github.rnachoc.mercadillo.checkout;

/**
 * One product in a cart or an order.
 *
 * @param sku the product
 * @param quantity how many units
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Line(String sku, int quantity) {
}
