package io.github.rnachoc.mercadillo.cart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps carts in Redis as JSON. A cart left alone for {@link #TIME_TO_LIVE} disappears.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CartStore {

    /** How long an untouched cart is kept. */
    static final Duration TIME_TO_LIVE = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /**
     * Creates the store.
     *
     * @param redis the Redis client
     * @param json the JSON mapper
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CartStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /**
     * Creates an empty cart.
     *
     * @return the new cart
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Cart create() {
        Cart cart = new Cart(UUID.randomUUID().toString(), List.of());
        save(cart);
        return cart;
    }

    /**
     * Reads a cart.
     *
     * @param id the cart's identifier
     * @return the cart, or empty when it does not exist or has expired
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Optional<Cart> find(String id) {
        String stored = redis.opsForValue().get(key(id));
        if (stored == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(stored, Cart.class));
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("cart " + id + " could not be read", corrupt);
        }
    }

    /**
     * Stores a cart and restarts its time to live.
     *
     * @param cart the cart
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public void save(Cart cart) {
        try {
            redis.opsForValue().set(key(cart.id()), json.writeValueAsString(cart), TIME_TO_LIVE);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cart " + cart.id() + " could not be written", impossible);
        }
    }

    /**
     * Deletes a cart.
     *
     * @param id the cart's identifier
     * @return {@code true} when a cart was deleted
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public boolean delete(String id) {
        return Boolean.TRUE.equals(redis.delete(key(id)));
    }

    /**
     * Builds the Redis key of a cart.
     *
     * @param id the cart's identifier
     * @return the key
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static String key(String id) {
        return "cart:" + id;
    }
}
