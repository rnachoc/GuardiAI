package io.github.rnachoc.mercadillo.notifier;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports what the notifier has done, for checks on the running stack.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class NotificationController {

    private final OrderPlacedListener listener;

    /**
     * Creates the controller.
     *
     * @param listener the listener that sends confirmations
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public NotificationController(OrderPlacedListener listener) {
        this.listener = listener;
    }

    /**
     * Returns how many confirmations were sent since the service started.
     *
     * @return the count
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @GetMapping("/notifications/stats")
    public Map<String, Long> stats() {
        return Map.of("sent", listener.sent());
    }
}
