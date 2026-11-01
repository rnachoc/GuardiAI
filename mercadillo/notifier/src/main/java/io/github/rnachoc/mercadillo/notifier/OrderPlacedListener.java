package io.github.rnachoc.mercadillo.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Sends an order confirmation for each event on {@code orders.placed}. Sending is simulated: the
 * confirmation is logged, and no message leaves the system.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class OrderPlacedListener {

    private static final Logger LOG = LoggerFactory.getLogger(OrderPlacedListener.class);
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    private final ObjectMapper json;
    private final AtomicLong sent = new AtomicLong();
    private final LongCounter notifications = GlobalOpenTelemetry.getMeter("mercadillo.notifier")
            .counterBuilder("mercadillo.notifications")
            .setDescription("Order confirmations handled, by outcome")
            .setUnit("{notification}")
            .build();

    /**
     * Creates the listener.
     *
     * @param json the JSON mapper
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public OrderPlacedListener(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Handles one event. A malformed event is logged and skipped, so one bad message cannot stop the queue.
     *
     * @param event the event, as JSON
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @KafkaListener(topics = "orders.placed", groupId = "notifier")
    public void onOrderPlaced(String event) {
        String orderId;
        try {
            JsonNode node = json.readTree(event);
            orderId = node.path("orderId").asText("");
        } catch (Exception malformed) {
            orderId = "";
        }
        if (orderId.isBlank()) {
            notifications.add(1, Attributes.of(OUTCOME, "malformed"));
            LOG.warn("Skipped a malformed order event");
            return;
        }
        sent.incrementAndGet();
        notifications.add(1, Attributes.of(OUTCOME, "sent"));
        LOG.info("Order confirmation sent for order {}", orderId);
    }

    /**
     * Returns how many confirmations this instance has sent since it started.
     *
     * @return the count
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public long sent() {
        return sent.get();
    }
}
