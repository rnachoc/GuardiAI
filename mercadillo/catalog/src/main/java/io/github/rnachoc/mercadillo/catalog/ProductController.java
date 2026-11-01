package io.github.rnachoc.mercadillo.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the catalog over HTTP.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class ProductController {

    private final ProductRepository products;

    /**
     * Creates the controller.
     *
     * @param products where products are read from
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public ProductController(ProductRepository products) {
        this.products = products;
    }

    /**
     * Lists products.
     *
     * @param category an optional category to filter by
     * @param limit how many products to return, from 1 to 100
     * @return the products
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/products")
    public List<Product> list(
            @RequestParam Optional<String> category,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return products.findAll(category, limit);
    }

    /**
     * Returns one product.
     *
     * @param sku the product's SKU
     * @return the product, or 404 when it does not exist
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/products/{sku}")
    public ResponseEntity<Product> get(@PathVariable String sku) {
        Product product = products.findBySku(sku);
        return product == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(product);
    }
}
