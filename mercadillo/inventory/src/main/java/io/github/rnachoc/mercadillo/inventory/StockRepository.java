package io.github.rnachoc.mercadillo.inventory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds and releases stock in the {@code inventory} schema. Each change runs in one transaction, so a
 * reservation either holds every line or none of them.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class StockRepository {

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the client for the service's database
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Holds stock for an order. Asking again for an order that already holds stock changes nothing.
     *
     * @param request the order and its lines
     * @return {@code true} when stock was held now, {@code false} when the order already held it
     * @throws InsufficientStockException when a line cannot be held; nothing is held then
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Transactional
    public boolean reserve(ReservationRequest request) {
        boolean claimed = jdbc.sql("insert into reservation_orders (order_id) values (:orderId) on conflict do nothing")
                .param("orderId", request.orderId())
                .update() == 1;
        if (!claimed) {
            return false;
        }
        // Lines are taken in SKU order, so two orders for the same products cannot deadlock.
        List<ReservationRequest.Item> lines = request.items().stream()
                .sorted(Comparator.comparing(ReservationRequest.Item::sku))
                .toList();
        for (ReservationRequest.Item item : lines) {
            int updated = jdbc.sql("update stock set available = available - :quantity "
                            + "where sku = :sku and available >= :quantity")
                    .param("quantity", item.quantity())
                    .param("sku", item.sku())
                    .update();
            if (updated != 1) {
                throw new InsufficientStockException(item.sku());
            }
            jdbc.sql("insert into reservation_lines (order_id, sku, quantity) values (:orderId, :sku, :quantity) "
                            + "on conflict (order_id, sku) do update set quantity = reservation_lines.quantity + excluded.quantity")
                    .param("orderId", request.orderId())
                    .param("sku", item.sku())
                    .param("quantity", item.quantity())
                    .update();
        }
        return true;
    }

    /**
     * Gives back the stock an order holds. Releasing an order that holds nothing changes nothing.
     *
     * @param orderId the order
     * @return {@code true} when stock was given back
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Transactional
    public boolean release(String orderId) {
        List<ReservationRequest.Item> lines = jdbc.sql("delete from reservation_lines where order_id = :orderId "
                        + "returning sku, quantity")
                .param("orderId", orderId)
                .query(ReservationRequest.Item.class)
                .list();
        for (ReservationRequest.Item line : lines) {
            jdbc.sql("update stock set available = available + :quantity where sku = :sku")
                    .param("quantity", line.quantity())
                    .param("sku", line.sku())
                    .update();
        }
        jdbc.sql("delete from reservation_orders where order_id = :orderId").param("orderId", orderId).update();
        return !lines.isEmpty();
    }

    /**
     * Reads how many units of a product are left.
     *
     * @param sku the product
     * @return the units available, or empty when the product is unknown
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Optional<Integer> available(String sku) {
        return jdbc.sql("select available from stock where sku = :sku").param("sku", sku).query(Integer.class).optional();
    }
}
