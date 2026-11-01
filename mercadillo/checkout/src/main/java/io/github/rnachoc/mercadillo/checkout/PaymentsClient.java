package io.github.rnachoc.mercadillo.checkout;

import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Charges orders through the payment provider.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class PaymentsClient {

    private final RestClient payments;

    /**
     * Creates the client.
     *
     * @param payments the HTTP client bound to the payment provider
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public PaymentsClient(@Qualifier("paymentsRestClient") RestClient payments) {
        this.payments = payments;
    }

    /**
     * Charges an order.
     *
     * @param orderId the order; the provider charges each order once
     * @param amountCents the amount, in cents
     * @param currency the ISO 4217 currency code
     * @param paymentToken the shopper's payment method
     * @return the provider's charge identifier
     * @throws CheckoutException with 402 when the charge is declined, or 502 when the provider fails
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public String charge(String orderId, long amountCents, String currency, String paymentToken) {
        return payments.post()
                .uri("/charges")
                .body(Map.of("orderId", orderId, "amountCents", amountCents, "currency", currency,
                        "paymentToken", paymentToken))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isSameCodeAs(HttpStatus.PAYMENT_REQUIRED)) {
                        throw new CheckoutException(HttpStatus.PAYMENT_REQUIRED, "declined", "the payment was declined");
                    }
                    Downstream.requireSuccess("payments", response.getStatusCode());
                    ChargeView charge = response.bodyTo(ChargeView.class);
                    if (charge == null || charge.chargeId() == null) {
                        throw new CheckoutException(HttpStatus.BAD_GATEWAY, "dependency_failed",
                                "payments answered without a charge");
                    }
                    return charge.chargeId();
                });
    }

    /**
     * The part of a charge that checkout reads.
     *
     * @param chargeId the provider's charge identifier
     * @param status what the provider decided
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    record ChargeView(String chargeId, String status) {
    }
}
