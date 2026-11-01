package io.github.rnachoc.mercadillo.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Checks the fake provider's decisions and that it charges each order once.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class PaymentServiceTest {

    private final PaymentService payments =
            new PaymentService(new PaymentsProperties(Duration.ZERO, Duration.ZERO, "tok_decline"));

    /**
     * A normal token is approved, for the amount asked.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void approvesANormalToken() {
        Charge charge = payments.charge(new ChargeRequest("order-1", 1299, "EUR", "tok_visa"));

        assertThat(charge.status()).isEqualTo(Charge.Status.APPROVED);
        assertThat(charge.amountCents()).isEqualTo(1299);
        assertThat(charge.chargeId()).startsWith("ch_");
    }

    /**
     * The decline token is always declined.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void declinesTheDeclineToken() {
        assertThat(payments.charge(new ChargeRequest("order-2", 1299, "EUR", "tok_decline")).status())
                .isEqualTo(Charge.Status.DECLINED);
    }

    /**
     * Charging the same order again returns the first charge, even with a different amount.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void chargesEachOrderOnce() {
        Charge first = payments.charge(new ChargeRequest("order-3", 1299, "EUR", "tok_visa"));
        Charge again = payments.charge(new ChargeRequest("order-3", 9999, "EUR", "tok_visa"));

        assertThat(again).isEqualTo(first);
    }

    /**
     * A latency range that ends before it starts is refused at start-up.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesAnInvertedLatencyRange() {
        assertThatThrownBy(() -> new PaymentsProperties(Duration.ofMillis(50), Duration.ofMillis(10), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
