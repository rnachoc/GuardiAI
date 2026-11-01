package io.github.rnachoc.mercadillo.inventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Checks the inventory's HTTP answers for reservations.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    private static final String RESERVATION = "{\"orderId\":\"o1\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":2}]}";

    @Autowired
    private MockMvc http;

    @MockitoBean
    private StockRepository stock;

    /**
     * A new reservation answers 201, and repeating it answers 200.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void answersCreatedThenOk() throws Exception {
        when(stock.reserve(any())).thenReturn(true, false);

        http.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION))
                .andExpect(status().isCreated());
        http.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION))
                .andExpect(status().isOk());
    }

    /**
     * Running short answers 409.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void answersConflictWhenStockRunsShort() throws Exception {
        when(stock.reserve(any())).thenThrow(new InsufficientStockException("SKU-0001"));

        http.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content(RESERVATION))
                .andExpect(status().isConflict());
    }

    /**
     * A reservation with no lines, or a line of zero units, is refused before the database is touched.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesEmptyOrZeroReservations() throws Exception {
        http.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"o1\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"o1\",\"items\":[{\"sku\":\"SKU-0001\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());

        verify(stock, never()).reserve(any());
    }

    /**
     * Releasing answers 204, whether or not anything was held.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void releasingAlwaysAnswersNoContent() throws Exception {
        when(stock.release("o1")).thenReturn(false);

        http.perform(delete("/reservations/o1")).andExpect(status().isNoContent());
    }
}
