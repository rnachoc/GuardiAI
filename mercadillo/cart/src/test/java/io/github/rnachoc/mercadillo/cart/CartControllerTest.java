package io.github.rnachoc.mercadillo.cart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks the cart's HTTP answers when adding products.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@WebMvcTest(CartController.class)
class CartControllerTest {

    @Autowired
    private MockMvc http;

    @MockitoBean
    private CartStore carts;

    @MockitoBean
    private CatalogClient catalog;

    /**
     * A product the catalog does not know is refused with 422, and the cart is not changed.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesAnUnknownProduct() throws Exception {
        when(carts.find("c1")).thenReturn(Optional.of(new Cart("c1", List.of())));
        when(catalog.exists("SKU-404")).thenReturn(false);

        http.perform(put("/carts/c1/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-404\",\"quantity\":1}"))
                .andExpect(status().isUnprocessableEntity());

        verify(carts, never()).save(any());
    }

    /**
     * A known product is added and the updated cart is returned.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void addsAKnownProduct() throws Exception {
        when(carts.find("c1")).thenReturn(Optional.of(new Cart("c1", List.of())));
        when(catalog.exists("SKU-0001")).thenReturn(true);

        http.perform(put("/carts/c1/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-0001\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        verify(carts).save(new Cart("c1", List.of(new Cart.Item("SKU-0001", 2))));
    }

    /**
     * A quantity outside 1 to the maximum is refused with 400 before the catalog is asked.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesAQuantityOutOfRange() throws Exception {
        http.perform(put("/carts/c1/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-0001\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());

        verify(catalog, never()).exists(any());
    }

    /**
     * An unknown cart answers 404.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void answersNotFoundForAnUnknownCart() throws Exception {
        when(carts.find("nope")).thenReturn(Optional.empty());

        http.perform(put("/carts/nope/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"SKU-0001\",\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }
}
