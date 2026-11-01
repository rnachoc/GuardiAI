package io.github.rnachoc.mercadillo.cart;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves carts over HTTP.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class CartController {

    private final CartStore carts;
    private final CatalogClient catalog;

    /**
     * Creates the controller.
     *
     * @param carts where carts are kept
     * @param catalog the catalog, which says whether a product exists
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CartController(CartStore carts, CatalogClient catalog) {
        this.carts = carts;
        this.catalog = catalog;
    }

    /**
     * Creates an empty cart.
     *
     * @return 201 with the cart
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/carts")
    public ResponseEntity<Cart> create() {
        return ResponseEntity.status(HttpStatus.CREATED).body(carts.create());
    }

    /**
     * Returns a cart.
     *
     * @param id the cart's identifier
     * @return the cart, or 404
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/carts/{id}")
    public ResponseEntity<Cart> get(@PathVariable String id) {
        return carts.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Adds units of a product to a cart, after checking the product exists.
     *
     * @param id the cart's identifier
     * @param line the product and quantity
     * @return the updated cart; 404 for an unknown cart; 422 for an unknown product or a cart over its limits
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PutMapping("/carts/{id}/items")
    public ResponseEntity<?> add(@PathVariable String id, @Valid @RequestBody Line line) {
        var found = carts.find(id);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!catalog.exists(line.sku())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "unknown product " + line.sku()));
        }
        Cart updated = found.get().add(line.sku(), line.quantity());
        carts.save(updated);
        return ResponseEntity.ok(updated);
    }

    /**
     * Deletes a cart.
     *
     * @param id the cart's identifier
     * @return 204 in every case, since deleting twice is harmless
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @DeleteMapping("/carts/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        carts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Answers 422 when a cart would grow past its limits.
     *
     * @param exception the limit that was hit
     * @return the 422 response
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> overLimit(IllegalArgumentException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", exception.getMessage()));
    }

    /**
     * A line to add to a cart.
     *
     * @param sku the product
     * @param quantity how many units, from 1 to {@link Cart#MAX_QUANTITY}
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Line(@NotBlank String sku, @Min(1) @Max(Cart.MAX_QUANTITY) int quantity) {
    }
}
