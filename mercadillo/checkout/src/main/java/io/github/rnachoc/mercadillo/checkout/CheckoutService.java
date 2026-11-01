package io.github.rnachoc.mercadillo.checkout;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Turns a cart into an order: prices it from the catalog, holds the stock, charges the shopper, records the
 * order and announces it. When anything fails after the stock is held, the stock is given back.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class CheckoutService {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);

    private final CartClient carts;
    private final CatalogClient catalog;
    private final InventoryClient inventory;
    private final PaymentsClient payments;
    private final OrderRepository orders;
    private final OrderEvents events;
    private final CheckoutMetrics metrics;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param carts the cart service
     * @param catalog the catalog, for prices
     * @param inventory the inventory, for stock
     * @param payments the payment provider
     * @param orders where orders are recorded
     * @param events where orders are announced
     * @param metrics the checkout counter
     * @param clock the clock that stamps orders
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CheckoutService(CartClient carts, CatalogClient catalog, InventoryClient inventory, PaymentsClient payments,
                           OrderRepository orders, OrderEvents events, CheckoutMetrics metrics, Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.inventory = inventory;
        this.payments = payments;
        this.orders = orders;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Places the order for a cart.
     *
     * @param cartId the cart
     * @param paymentToken the shopper's payment method
     * @return the placed order
     * @throws CheckoutException when the order cannot be placed, with the status for the shopper
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Order checkout(String cartId, String paymentToken) {
        try {
            Order order = place(cartId, paymentToken);
            metrics.record("placed");
            return order;
        } catch (CheckoutException failure) {
            metrics.record(failure.outcome());
            throw failure;
        } catch (RuntimeException failure) {
            metrics.record("failed");
            throw failure;
        }
    }

    /**
     * Does the work of {@link #checkout}, without counting it.
     *
     * @param cartId the cart
     * @param paymentToken the shopper's payment method
     * @return the placed order
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Order place(String cartId, String paymentToken) {
        List<Line> lines = carts.lines(cartId)
                .orElseThrow(() -> new CheckoutException(HttpStatus.NOT_FOUND, "unknown_cart", "cart not found"));
        if (lines.isEmpty()) {
            throw new CheckoutException(HttpStatus.UNPROCESSABLE_ENTITY, "empty_cart", "the cart is empty");
        }
        List<Order.PricedLine> priced = new ArrayList<>();
        String currency = null;
        long total = 0;
        for (Line line : lines) {
            CatalogClient.Price price = catalog.price(line.sku());
            if (currency != null && !currency.equals(price.currency())) {
                throw new CheckoutException(HttpStatus.UNPROCESSABLE_ENTITY, "mixed_currencies",
                        "the cart mixes currencies");
            }
            currency = price.currency();
            total = Math.addExact(total, Math.multiplyExact(price.priceCents(), line.quantity()));
            priced.add(new Order.PricedLine(line.sku(), line.quantity(), price.priceCents()));
        }

        String orderId = UUID.randomUUID().toString();
        inventory.reserve(orderId, lines);
        String chargeId;
        try {
            chargeId = payments.charge(orderId, total, currency, paymentToken);
        } catch (RuntimeException notCharged) {
            releaseQuietly(orderId);
            throw notCharged;
        }

        Order order = new Order(orderId, cartId, priced, total, currency, chargeId, clock.instant());
        orders.save(order);
        events.placed(order);
        try {
            carts.delete(cartId);
        } catch (RuntimeException leftOver) {
            LOG.warn("Order {} was placed but its cart could not be cleared: {}", orderId, leftOver.toString());
        }
        LOG.info("Order {} placed: {} line(s), {} {}", orderId, priced.size(), total, currency);
        return order;
    }

    /**
     * Gives back the stock of an order that will not be placed, without hiding the original failure.
     *
     * @param orderId the order
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void releaseQuietly(String orderId) {
        try {
            inventory.release(orderId);
        } catch (RuntimeException releaseFailed) {
            LOG.error("Stock held for order {} could not be released: {}", orderId, releaseFailed.toString());
        }
    }
}
