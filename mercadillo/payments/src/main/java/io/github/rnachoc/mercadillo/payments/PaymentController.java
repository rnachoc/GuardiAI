package io.github.rnachoc.mercadillo.payments;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves charges over HTTP.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class PaymentController {

    private final PaymentService payments;

    /**
     * Creates the controller.
     *
     * @param payments the service that decides charges
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * Charges an order.
     *
     * @param request the charge
     * @return 201 with the charge when approved, 402 with the charge when declined
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @PostMapping("/charges")
    public ResponseEntity<Charge> charge(@Valid @RequestBody ChargeRequest request) {
        Charge charge = payments.charge(request);
        HttpStatus status = charge.status() == Charge.Status.APPROVED ? HttpStatus.CREATED : HttpStatus.PAYMENT_REQUIRED;
        return ResponseEntity.status(status).body(charge);
    }
}
