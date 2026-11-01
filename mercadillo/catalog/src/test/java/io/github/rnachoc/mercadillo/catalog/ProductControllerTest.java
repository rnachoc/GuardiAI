package io.github.rnachoc.mercadillo.catalog;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks the catalog's HTTP answers.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc http;

    @MockitoBean
    private ProductRepository products;

    /**
     * A known product is returned with its price in cents.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void returnsAKnownProduct() throws Exception {
        when(products.findBySku("SKU-0001")).thenReturn(new Product("SKU-0001", "Cast iron pan", "kitchen", 3499, "EUR"));

        http.perform(get("/products/SKU-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCents").value(3499));
    }

    /**
     * An unknown product answers 404.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void answersNotFoundForAnUnknownProduct() throws Exception {
        http.perform(get("/products/SKU-404")).andExpect(status().isNotFound());
    }

    /**
     * A category filter reaches the repository, and the page size defaults to 20.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void passesTheCategoryAndDefaultLimit() throws Exception {
        when(products.findAll(Optional.of("books"), 20)).thenReturn(List.of());

        http.perform(get("/products").param("category", "books")).andExpect(status().isOk());

        verify(products).findAll(Optional.of("books"), 20);
    }

    /**
     * A page size above 100 is refused with 400, so nobody can ask for the whole table at once.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesAPageSizeAboveTheLimit() throws Exception {
        http.perform(get("/products").param("limit", "101")).andExpect(status().isBadRequest());

        verify(products, never()).findAll(any(), anyInt());
    }
}
