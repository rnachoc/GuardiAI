package io.github.rnachoc.mercadillo.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces placed orders on the {@value #TOPIC} topic. The event names products and amounts, never a person.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class OrderEvents {

    /** The topic placed orders are announced on. */
    public static final String TOPIC = "orders.placed";

    private static final Logger LOG = LoggerFactory.getLogger(OrderEvents.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    /**
     * Creates the publisher.
     *
     * @param kafka the Kafka producer
     * @param json the JSON mapper
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public OrderEvents(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /**
     * Announces an order and waits for the broker to acknowledge it. The order is already recorded and paid,
     * so a failure here is logged and does not undo it; the customer then receives no confirmation.
     *
     * @param order the placed order
     * @return {@code true} when the broker acknowledged the event
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public boolean placed(Order order) {
        try {
            String event = json.writeValueAsString(new OrderPlaced(order.id(), order.totalCents(), order.currency(),
                    order.lines().stream().map(line -> new Line(line.sku(), line.quantity())).toList(),
                    order.placedAt().toString()));
            kafka.send(TOPIC, order.id(), event).get(5, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.error("Order {} was placed but not announced: interrupted", order.id());
        } catch (ExecutionException | TimeoutException | JsonProcessingException failure) {
            LOG.error("Order {} was placed but not announced: {}", order.id(), failure.toString());
        }
        return false;
    }

    /**
     * The event announcing a placed order.
     *
     * @param orderId the order
     * @param totalCents the total, in cents
     * @param currency the ISO 4217 currency code
     * @param items what was bought
     * @param placedAt when, as an ISO 8601 instant
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    record OrderPlaced(String orderId, long totalCents, String currency, List<Line> items, String placedAt) {
    }
}
