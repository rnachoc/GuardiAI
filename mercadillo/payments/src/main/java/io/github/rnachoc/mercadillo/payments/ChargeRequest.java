package io.github.rnachoc.mercadillo.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * A request to charge an order.
 *
 * @param orderId the order; charging the same order twice charges it once
 * @param amountCents the amount, in cents
 * @param currency the ISO 4217 currency code
 * @param paymentToken the shopper's payment method, as a token; never a card number
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record ChargeRequest(
        @NotBlank String orderId,
        @Positive long amountCents,
        @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Pattern(regexp = "tok_[A-Za-z0-9_]{1,40}") String paymentToken) {
}
