package io.github.rnachoc.mercadillo.payments;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * Decides charges like a payment provider would, without moving money. Each order is charged at most once:
 * repeating a charge returns the first outcome, for as long as the provider remembers the order (the last
 * {@value #REMEMBERED_ORDERS} orders), which bounds its memory under steady load.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class PaymentService {

    static final int REMEMBERED_ORDERS = 100_000;

    private final PaymentsProperties properties;
    private final Map<String, Charge> chargesByOrder = Collections.synchronizedMap(new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        /**
         * Forgets the oldest order once the provider remembers more than {@value #REMEMBERED_ORDERS}.
         *
         * @param eldest the oldest remembered order
         * @return {@code true} to forget it
         * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Charge> eldest) {
            return size() > REMEMBERED_ORDERS;
        }
    });

    /**
     * Creates the service.
     *
     * @param properties how the fake provider behaves
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public PaymentService(PaymentsProperties properties) {
        this.properties = properties;
    }

    /**
     * Charges an order, or returns the outcome of the first charge for it.
     *
     * @param request the charge
     * @return the charge, approved or declined
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Charge charge(ChargeRequest request) {
        Charge known = chargesByOrder.get(request.orderId());
        if (known != null) {
            return known;
        }
        // The decision is made outside the lock, so a slow charge never holds up the others. When two
        // requests for one order race, the first stored outcome wins and both callers receive it.
        Charge decided = decide(request);
        Charge first = chargesByOrder.putIfAbsent(request.orderId(), decided);
        return first == null ? decided : first;
    }

    /**
     * Makes the decision for a new charge, after a provider-like delay.
     *
     * @param request the charge
     * @return the decision
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Charge decide(ChargeRequest request) {
        pause();
        Charge.Status status = properties.declineToken().equals(request.paymentToken())
                ? Charge.Status.DECLINED
                : Charge.Status.APPROVED;
        return new Charge("ch_" + UUID.randomUUID(), request.orderId(), request.amountCents(), request.currency(), status);
    }

    /**
     * Waits for a random time inside the configured latency range.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void pause() {
        long min = properties.minLatency().toMillis();
        long max = properties.maxLatency().toMillis();
        long millis = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
