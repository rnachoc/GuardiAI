package io.github.rnachoc.mercadillo.payments;

/**
 * The outcome of a charge.
 *
 * @param chargeId the provider's identifier for the charge
 * @param orderId the order charged
 * @param amountCents the amount, in cents
 * @param currency the ISO 4217 currency code
 * @param status whether the charge was approved or declined
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Charge(String chargeId, String orderId, long amountCents, String currency, Status status) {

    /**
     * What the provider decided.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public enum Status {
        APPROVED,
        DECLINED
    }
}
