package io.github.rnachoc.mercadillo.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/**
 * Checks how checkout orders its steps, and that held stock is given back whenever an order is not placed.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class CheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");

    private CartClient carts;
    private CatalogClient catalog;
    private InventoryClient inventory;
    private PaymentsClient payments;
    private OrderRepository orders;
    private OrderEvents events;
    private CheckoutMetrics metrics;
    private CheckoutService service;

    /**
     * Builds the service on mocks of everything it talks to.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void setUp() {
        carts = mock(CartClient.class);
        catalog = mock(CatalogClient.class);
        inventory = mock(InventoryClient.class);
        payments = mock(PaymentsClient.class);
        orders = mock(OrderRepository.class);
        events = mock(OrderEvents.class);
        metrics = mock(CheckoutMetrics.class);
        service = new CheckoutService(carts, catalog, inventory, payments, orders, events, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(catalog.price("SKU-0001")).thenReturn(new CatalogClient.Price("SKU-0001", 1299, "EUR"));
        when(catalog.price("SKU-0002")).thenReturn(new CatalogClient.Price("SKU-0002", 500, "EUR"));
    }

    /**
     * A good cart is priced from the catalog, held, charged the right total, recorded, announced and cleared.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void placesAnOrderAtCatalogPrices() {
        when(carts.lines("cart-1")).thenReturn(Optional.of(List.of(new Line("SKU-0001", 2), new Line("SKU-0002", 1))));
        when(payments.charge(anyString(), anyLong(), anyString(), anyString())).thenReturn("ch_1");

        Order order = service.checkout("cart-1", "tok_ok");

        assertThat(order.totalCents()).isEqualTo(2 * 1299 + 500);
        assertThat(order.currency()).isEqualTo("EUR");
        assertThat(order.chargeId()).isEqualTo("ch_1");
        assertThat(order.placedAt()).isEqualTo(NOW);
        verify(inventory).reserve(eq(order.id()), any());
        verify(payments).charge(order.id(), 3098, "EUR", "tok_ok");
        verify(orders).save(order);
        verify(events).placed(order);
        verify(carts).delete("cart-1");
        verify(inventory, never()).release(anyString());
        verify(metrics).record("placed");
    }

    /**
     * A declined payment gives the held stock back and records nothing.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void releasesStockWhenThePaymentIsDeclined() {
        when(carts.lines("cart-1")).thenReturn(Optional.of(List.of(new Line("SKU-0001", 1))));
        when(payments.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new CheckoutException(HttpStatus.PAYMENT_REQUIRED, "declined", "the payment was declined"));

        assertThatThrownBy(() -> service.checkout("cart-1", "tok_decline"))
                .isInstanceOfSatisfying(CheckoutException.class,
                        failure -> assertThat(failure.status()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        ArgumentCaptor<String> held = ArgumentCaptor.forClass(String.class);
        verify(inventory).reserve(held.capture(), any());
        verify(inventory).release(held.getValue());
        verify(orders, never()).save(any());
        verify(events, never()).placed(any());
        verify(metrics).record("declined");
    }

    /**
     * When the payment provider cannot be reached, the stock is given back and the shopper sees the failure.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void releasesStockWhenThePaymentProviderFails() {
        when(carts.lines("cart-1")).thenReturn(Optional.of(List.of(new Line("SKU-0001", 1))));
        when(payments.charge(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timed out"));

        assertThatThrownBy(() -> service.checkout("cart-1", "tok_ok"))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);

        verify(inventory).release(anyString());
        verify(metrics).record("failed");
    }

    /**
     * When stock runs out, nobody is charged.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void chargesNothingWhenStockRunsOut() {
        when(carts.lines("cart-1")).thenReturn(Optional.of(List.of(new Line("SKU-0001", 1))));
        doThrow(new CheckoutException(HttpStatus.CONFLICT, "out_of_stock", "a product ran out of stock"))
                .when(inventory).reserve(anyString(), any());

        assertThatThrownBy(() -> service.checkout("cart-1", "tok_ok")).isInstanceOf(CheckoutException.class);

        verify(payments, never()).charge(anyString(), anyLong(), anyString(), anyString());
        verify(metrics).record("out_of_stock");
    }

    /**
     * An unknown cart answers 404 and an empty one 422, before any stock is held.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesUnknownAndEmptyCartsBeforeHoldingStock() {
        when(carts.lines("missing")).thenReturn(Optional.empty());
        when(carts.lines("empty")).thenReturn(Optional.of(List.of()));

        assertThatThrownBy(() -> service.checkout("missing", "tok_ok"))
                .isInstanceOfSatisfying(CheckoutException.class,
                        failure -> assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.checkout("empty", "tok_ok"))
                .isInstanceOfSatisfying(CheckoutException.class,
                        failure -> assertThat(failure.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(inventory, never()).reserve(anyString(), any());
    }

    /**
     * A placed order stays placed when its cart cannot be cleared afterwards.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void keepsTheOrderWhenTheCartCannotBeCleared() {
        when(carts.lines("cart-1")).thenReturn(Optional.of(List.of(new Line("SKU-0001", 1))));
        when(payments.charge(anyString(), anyLong(), anyString(), anyString())).thenReturn("ch_1");
        doThrow(new org.springframework.web.client.ResourceAccessException("down")).when(carts).delete("cart-1");

        Order order = service.checkout("cart-1", "tok_ok");

        assertThat(order.chargeId()).isEqualTo("ch_1");
        verify(orders).save(order);
        verify(metrics).record("placed");
    }
}
