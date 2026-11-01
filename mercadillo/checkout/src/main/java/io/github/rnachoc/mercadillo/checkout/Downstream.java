package io.github.rnachoc.mercadillo.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Turns an unexpected answer from another service into the shopper's 502.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
final class Downstream {

    /**
     * Prevents instances; this class only holds a helper.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Downstream() {
    }

    /**
     * Fails unless a service answered with success.
     *
     * @param service the service's name, for the message
     * @param status what it answered
     * @throws CheckoutException with 502 when the answer is not a success
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    static void requireSuccess(String service, HttpStatusCode status) {
        if (!status.is2xxSuccessful()) {
            throw new CheckoutException(HttpStatus.BAD_GATEWAY, "dependency_failed",
                    service + " answered " + status.value());
        }
    }
}
