package io.github.rnachoc.mercadillo.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves checkouts over HTTP.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class CheckoutController {

    private final CheckoutService checkouts;

    /**
     * Creates the controller.
     *
     * @param checkouts the service that places orders
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public CheckoutController(CheckoutService checkouts) {
        this.checkouts = checkouts;
    }

    /**
     * Places the order for a cart.
     *
     * @param request the cart and the payment method
     * @return 201 with the order
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/checkouts")
    public ResponseEntity<Order> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checkouts.checkout(request.cartId(), request.paymentToken()));
    }

    /**
     * Answers a failed checkout with its status and a message safe to show.
     *
     * @param failure why the checkout failed
     * @return the response
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @ExceptionHandler(CheckoutException.class)
    public ResponseEntity<Map<String, String>> failed(CheckoutException failure) {
        return ResponseEntity.status(failure.status())
                .body(Map.of("error", failure.getMessage(), "outcome", failure.outcome()));
    }

    /**
     * A request to check out.
     *
     * @param cartId the cart
     * @param paymentToken the shopper's payment method, as a token
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record CheckoutRequest(
            @NotBlank String cartId,
            @NotBlank @Pattern(regexp = "tok_[A-Za-z0-9_]{1,40}") String paymentToken) {
    }
}
