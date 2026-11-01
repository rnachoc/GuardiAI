package io.github.rnachoc.mercadillo.catalog;

import java.io.Serializable;

/**
 * A product on sale. Prices are whole cents, so no rounding ever happens.
 *
 * @param sku the stock-keeping unit, which identifies the product
 * @param name the name shown to shoppers
 * @param category the aisle the product belongs to
 * @param priceCents the price, in cents of {@code currency}
 * @param currency the ISO 4217 currency code
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record Product(String sku, String name, String category, long priceCents, String currency)
        implements Serializable {
}
