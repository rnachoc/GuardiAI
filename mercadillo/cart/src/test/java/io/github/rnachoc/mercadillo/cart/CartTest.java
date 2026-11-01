package io.github.rnachoc.mercadillo.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks how a cart merges lines and keeps to its limits.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class CartTest {

    /**
     * Adding a product already in the cart grows its line instead of adding another.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void mergesUnitsOfTheSameProduct() {
        Cart cart = new Cart("c", List.of()).add("SKU-0001", 2).add("SKU-0002", 1).add("SKU-0001", 3);

        assertThat(cart.items()).containsExactly(new Cart.Item("SKU-0001", 5), new Cart.Item("SKU-0002", 1));
    }

    /**
     * A line cannot grow past the most units of one product.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesTooManyUnitsOfOneProduct() {
        Cart cart = new Cart("c", List.of()).add("SKU-0001", Cart.MAX_QUANTITY);

        assertThatThrownBy(() -> cart.add("SKU-0001", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A cart cannot hold more than the most distinct products.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesTooManyProducts() {
        Cart cart = new Cart("c", List.of());
        for (int i = 1; i <= Cart.MAX_LINES; i++) {
            cart = cart.add("SKU-%04d".formatted(i), 1);
        }
        Cart full = cart;

        assertThatThrownBy(() -> full.add("SKU-9999", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
