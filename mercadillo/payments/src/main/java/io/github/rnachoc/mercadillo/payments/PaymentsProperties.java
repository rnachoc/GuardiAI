package io.github.rnachoc.mercadillo.payments;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the fake provider behaves.
 *
 * @param minLatency the shortest time a charge takes, to look like a real provider
 * @param maxLatency the longest time a charge takes
 * @param declineToken the payment token that is always declined, so tests and load can exercise refusals
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@ConfigurationProperties("payments")
public record PaymentsProperties(Duration minLatency, Duration maxLatency, String declineToken) {

    /**
     * Fills in defaults and checks the latency range.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public PaymentsProperties {
        minLatency = minLatency == null ? Duration.ofMillis(20) : minLatency;
        maxLatency = maxLatency == null ? Duration.ofMillis(60) : maxLatency;
        declineToken = declineToken == null ? "tok_decline" : declineToken;
        if (maxLatency.compareTo(minLatency) < 0) {
            throw new IllegalArgumentException("payments.max-latency must not be shorter than payments.min-latency");
        }
    }
}
