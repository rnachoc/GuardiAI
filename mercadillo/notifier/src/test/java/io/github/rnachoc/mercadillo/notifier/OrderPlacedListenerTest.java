package io.github.rnachoc.mercadillo.notifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Checks that the notifier confirms good events and survives bad ones.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class OrderPlacedListenerTest {

    private final OrderPlacedListener listener = new OrderPlacedListener(new ObjectMapper());

    /**
     * An event with an order sends one confirmation.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void confirmsAnOrder() {
        listener.onOrderPlaced("{\"orderId\":\"o1\",\"totalCents\":1299,\"currency\":\"EUR\"}");

        assertThat(listener.sent()).isEqualTo(1);
    }

    /**
     * Malformed JSON, or an event with no order, is skipped without an exception.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void skipsMalformedEvents() {
        listener.onOrderPlaced("not json at all");
        listener.onOrderPlaced("{\"totalCents\":1299}");
        listener.onOrderPlaced("{\"orderId\":\"\"}");

        assertThat(listener.sent()).isZero();
    }
}
