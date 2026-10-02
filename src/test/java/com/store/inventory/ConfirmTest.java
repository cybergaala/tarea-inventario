package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("STD", ProductCategory.STANDARD);
        service.addStock("STD", 10);
    }

    @Test
    void confirmedUnitsDoNotReturnWhenPaymentWindowPasses() {
        service.reserve("O-1", "STD", 4);
        service.confirm("O-1");
        clock.advance(Duration.ofDays(2));
        assertEquals(6, service.available("STD"));
    }

    @Test
    void confirmJustBeforeExpirySucceeds() {
        service.reserve("O-1", "STD", 4);
        clock.advance(Duration.ofMinutes(15).minusNanos(1));
        service.confirm("O-1");
        assertEquals(6, service.available("STD"));
    }

    @Test
    void cannotConfirmExpiredReservation() {
        service.reserve("O-1", "STD", 4);
        clock.advance(Duration.ofMinutes(15));
        assertThrows(IllegalStateException.class, () -> service.confirm("O-1"));
        assertEquals(10, service.available("STD"));
    }

    @Test
    void cannotConfirmUnknownOrder() {
        assertThrows(IllegalStateException.class, () -> service.confirm("NOPE"));
        assertThrows(IllegalStateException.class, () -> service.confirm(null));
    }

    @Test
    void cannotConfirmTwice() {
        service.reserve("O-1", "STD", 4);
        service.confirm("O-1");
        assertThrows(IllegalStateException.class, () -> service.confirm("O-1"));
        assertEquals(6, service.available("STD"));
    }

    @Test
    void cannotConfirmOrderWhoseReservationFailed() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("O-1", "STD", 11));
        assertThrows(IllegalStateException.class, () -> service.confirm("O-1"));
    }

    @Test
    void lateRetryOfPaidOrderReturnsTheSaleWithoutReservingAgain() {
        Reservation reservation = service.reserve("O-1", "STD", 4);
        service.confirm("O-1");
        assertEquals(reservation, service.reserve("O-1", "STD", 4));
        assertEquals(6, service.available("STD"));
    }

    @Test
    void soldUnitsAreNotAvailableToOtherOrders() {
        service.reserve("O-1", "STD", 10);
        service.confirm("O-1");
        clock.advance(Duration.ofMinutes(15));
        assertThrows(InsufficientStockException.class, () -> service.reserve("O-2", "STD", 1));
    }
}
