package io.github.rnachoc.mercadillo.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where each service behind the gateway lives.
 *
 * @param catalogUrl the catalog's base URL
 * @param cartUrl the cart service's base URL
 * @param checkoutUrl the checkout service's base URL
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@ConfigurationProperties("mercadillo")
public record Routes(String catalogUrl, String cartUrl, String checkoutUrl) {
}
