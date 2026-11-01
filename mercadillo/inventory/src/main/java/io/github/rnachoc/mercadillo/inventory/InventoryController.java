package io.github.rnachoc.mercadillo.inventory;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves stock levels and reservations over HTTP.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class InventoryController {

    private final StockRepository stock;

    /**
     * Creates the controller.
     *
     * @param stock where stock is kept
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public InventoryController(StockRepository stock) {
        this.stock = stock;
    }

    /**
     * Holds stock for an order.
     *
     * @param request the order and its lines
     * @return 201 when stock was held now, 200 when the order already held it
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/reservations")
    public ResponseEntity<Map<String, String>> reserve(@Valid @RequestBody ReservationRequest request) {
        boolean created = stock.reserve(request);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(Map.of("orderId", request.orderId(), "status", "RESERVED"));
    }

    /**
     * Gives back the stock an order holds.
     *
     * @param orderId the order
     * @return 204 in every case, since releasing twice is harmless
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @DeleteMapping("/reservations/{orderId}")
    public ResponseEntity<Void> release(@PathVariable String orderId) {
        stock.release(orderId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reads how many units of a product are left.
     *
     * @param sku the product
     * @return the units available, or 404 for an unknown product
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/stock/{sku}")
    public ResponseEntity<Map<String, Object>> available(@PathVariable String sku) {
        return stock.available(sku)
                .map(units -> ResponseEntity.ok(Map.<String, Object>of("sku", sku, "available", units)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Answers 409 when a product ran short, naming no internal detail beyond the product.
     *
     * @param exception what ran short
     * @return the 409 response
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> insufficient(InsufficientStockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }
}
