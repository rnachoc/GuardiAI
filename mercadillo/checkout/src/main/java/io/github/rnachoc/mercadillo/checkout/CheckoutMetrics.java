package io.github.rnachoc.mercadillo.checkout;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import org.springframework.stereotype.Component;

/**
 * Counts checkouts by outcome. The OpenTelemetry agent exports the counter; without the agent it does nothing.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CheckoutMetrics {

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");

    private final LongCounter checkouts = GlobalOpenTelemetry.getMeter("mercadillo.checkout")
            .counterBuilder("mercadillo.checkouts")
            .setDescription("Checkouts attempted, by outcome")
            .setUnit("{checkout}")
            .build();

    /**
     * Counts one checkout.
     *
     * @param outcome how it ended, such as {@code placed} or {@code declined}
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public void record(String outcome) {
        checkouts.add(1, Attributes.of(OUTCOME, outcome));
    }
}
