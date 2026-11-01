package io.github.rnachoc.mercadillo.gateway;

import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The shop's public API. Each route names exactly one endpoint of one service, so nothing else behind the
 * gateway can be reached from outside.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api")
public class GatewayController {

    private final Forwarder forwarder;
    private final Routes routes;

    /**
     * Creates the controller.
     *
     * @param forwarder what forwards requests
     * @param routes where each service lives
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public GatewayController(Forwarder forwarder, Routes routes) {
        this.forwarder = forwarder;
        this.routes = routes;
    }

    /**
     * Lists products.
     *
     * @param category an optional category
     * @param limit an optional page size
     * @return the catalog's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/products")
    public ResponseEntity<String> products(@RequestParam(required = false) String category,
                                           @RequestParam(required = false) Integer limit) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(routes.catalogUrl()).path("/products");
        if (category != null) {
            uri.queryParam("category", category);
        }
        if (limit != null) {
            uri.queryParam("limit", limit);
        }
        return forwarder.forward(HttpMethod.GET, uri.encode().build().toUri(), null);
    }

    /**
     * Returns one product.
     *
     * @param sku the product
     * @return the catalog's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/products/{sku}")
    public ResponseEntity<String> product(@PathVariable String sku) {
        return forwarder.forward(HttpMethod.GET, target(routes.catalogUrl(), "/products/{sku}", sku), null);
    }

    /**
     * Creates a cart.
     *
     * @return the cart service's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/carts")
    public ResponseEntity<String> createCart() {
        return forwarder.forward(HttpMethod.POST, target(routes.cartUrl(), "/carts"), null);
    }

    /**
     * Returns a cart.
     *
     * @param id the cart
     * @return the cart service's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/carts/{id}")
    public ResponseEntity<String> cart(@PathVariable String id) {
        return forwarder.forward(HttpMethod.GET, target(routes.cartUrl(), "/carts/{id}", id), null);
    }

    /**
     * Adds a product to a cart.
     *
     * @param id the cart
     * @param line the product and quantity, as JSON
     * @return the cart service's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PutMapping("/carts/{id}/items")
    public ResponseEntity<String> addToCart(@PathVariable String id, @RequestBody String line) {
        return forwarder.forward(HttpMethod.PUT, target(routes.cartUrl(), "/carts/{id}/items", id), line);
    }

    /**
     * Deletes a cart.
     *
     * @param id the cart
     * @return the cart service's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @DeleteMapping("/carts/{id}")
    public ResponseEntity<String> deleteCart(@PathVariable String id) {
        return forwarder.forward(HttpMethod.DELETE, target(routes.cartUrl(), "/carts/{id}", id), null);
    }

    /**
     * Checks out a cart.
     *
     * @param request the cart and payment method, as JSON
     * @return the checkout service's answer
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/checkouts")
    public ResponseEntity<String> checkout(@RequestBody String request) {
        return forwarder.forward(HttpMethod.POST, target(routes.checkoutUrl(), "/checkouts"), request);
    }

    /**
     * Builds a service URL, encoding each path variable so a shopper cannot change the path.
     *
     * @param base the service's base URL
     * @param path the path template
     * @param variables the values for the template
     * @return the URL
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static URI target(String base, String path, Object... variables) {
        return UriComponentsBuilder.fromUriString(base).path(path).buildAndExpand(variables).encode().toUri();
    }
}
