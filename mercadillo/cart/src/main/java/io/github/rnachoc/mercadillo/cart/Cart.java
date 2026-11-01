package io.github.rnachoc.mercadillo.cart;

import java.util.ArrayList;
import java.util.List;

/**
 * A shopper's cart: what they intend to buy, before checkout prices it.
 *
 * @param id the cart's identifier
 * @param items the lines, at most one per product
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Cart(String id, List<Item> items) {

    /** The most units of one product a cart may hold. */
    public static final int MAX_QUANTITY = 20;

    /** The most distinct products a cart may hold. */
    public static final int MAX_LINES = 30;

    /**
     * Keeps the cart's list immutable.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Cart {
        items = List.copyOf(items);
    }

    /**
     * Returns a copy of the cart with units of a product added, merged into its existing line.
     *
     * @param sku the product
     * @param quantity how many units to add
     * @return the new cart
     * @throws IllegalArgumentException when the line or the cart would grow past its limits
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Cart add(String sku, int quantity) {
        List<Item> next = new ArrayList<>();
        boolean merged = false;
        for (Item item : items) {
            if (item.sku().equals(sku)) {
                next.add(new Item(sku, item.quantity() + quantity));
                merged = true;
            } else {
                next.add(item);
            }
        }
        if (!merged) {
            next.add(new Item(sku, quantity));
        }
        if (next.size() > MAX_LINES) {
            throw new IllegalArgumentException("a cart holds at most " + MAX_LINES + " products");
        }
        if (next.stream().anyMatch(item -> item.quantity() > MAX_QUANTITY)) {
            throw new IllegalArgumentException("a cart holds at most " + MAX_QUANTITY + " units of a product");
        }
        return new Cart(id, next);
    }

    /**
     * One product in the cart.
     *
     * @param sku the product
     * @param quantity how many units
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Item(String sku, int quantity) {
    }
}
