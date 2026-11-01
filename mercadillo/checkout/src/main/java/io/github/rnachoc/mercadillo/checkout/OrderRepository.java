package io.github.rnachoc.mercadillo.checkout;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records placed orders in the {@code checkout} schema.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the client for the service's database
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an order and its lines in one transaction.
     *
     * @param order the order
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Transactional
    public void save(Order order) {
        jdbc.sql("insert into orders (id, cart_id, total_cents, currency, charge_id, placed_at) "
                        + "values (:id, :cartId, :total, :currency, :chargeId, :placedAt)")
                .param("id", UUID.fromString(order.id()))
                .param("cartId", order.cartId())
                .param("total", order.totalCents())
                .param("currency", order.currency())
                .param("chargeId", order.chargeId())
                .param("placedAt", Timestamp.from(order.placedAt()))
                .update();
        for (Order.PricedLine line : order.lines()) {
            jdbc.sql("insert into order_lines (order_id, sku, quantity, unit_price_cents) "
                            + "values (:orderId, :sku, :quantity, :unitPrice)")
                    .param("orderId", UUID.fromString(order.id()))
                    .param("sku", line.sku())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPriceCents())
                    .update();
        }
    }
}
