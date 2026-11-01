package io.github.rnachoc.mercadillo.inventory;

/**
 * Raised when a product does not have enough units left to hold, which undoes the whole reservation.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class InsufficientStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param sku the product that ran short
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public InsufficientStockException(String sku) {
        super("not enough stock for " + sku);
    }
}
