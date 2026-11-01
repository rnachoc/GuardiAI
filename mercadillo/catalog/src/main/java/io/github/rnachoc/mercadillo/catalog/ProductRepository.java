package io.github.rnachoc.mercadillo.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads products from the {@code catalog} schema. A product looked up by SKU is cached in Redis.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class ProductRepository {

    private static final String COLUMNS = "sku, name, category, price_cents, currency";

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the client for the service's database
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds one product. The result is cached, and a missing product is not.
     *
     * @param sku the product's SKU
     * @return the product, or empty when no product has that SKU
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Cacheable(cacheNames = "products", unless = "#result == null")
    public Product findBySku(String sku) {
        return jdbc.sql("select " + COLUMNS + " from products where sku = :sku")
                .param("sku", sku)
                .query(Product.class)
                .optional()
                .orElse(null);
    }

    /**
     * Lists products, optionally from one category, in SKU order.
     *
     * @param category the category to keep, or empty for every category
     * @param limit the maximum number of products to return
     * @return the products found
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public List<Product> findAll(Optional<String> category, int limit) {
        if (category.isPresent()) {
            return jdbc.sql("select " + COLUMNS + " from products where category = :category order by sku limit :limit")
                    .param("category", category.get())
                    .param("limit", limit)
                    .query(Product.class)
                    .list();
        }
        return jdbc.sql("select " + COLUMNS + " from products order by sku limit :limit")
                .param("limit", limit)
                .query(Product.class)
                .list();
    }
}
