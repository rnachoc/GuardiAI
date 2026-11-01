package io.github.rnachoc.mercadillo.checkout;

import org.springframework.http.HttpStatus;

/**
 * Raised when a checkout cannot complete. It carries the HTTP status the shopper receives.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class CheckoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String outcome;

    /**
     * Creates the exception.
     *
     * @param status the HTTP status for the shopper
     * @param outcome a short code for metrics and logs, such as {@code declined}
     * @param message what went wrong, safe to show to the shopper
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CheckoutException(HttpStatus status, String outcome, String message) {
        super(message);
        this.status = status;
        this.outcome = outcome;
    }

    /**
     * Returns the HTTP status for the shopper.
     *
     * @return the status
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Returns the short code for metrics and logs.
     *
     * @return the outcome
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public String outcome() {
        return outcome;
    }
}
