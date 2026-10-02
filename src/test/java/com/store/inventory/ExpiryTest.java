package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExpiryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("STD", ProductCategory.STANDARD);
        service.registerProduct("PRE", ProductCategory.PRE_ORDER);
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("STD", 10);
        service.addStock("PRE", 10);
        service.addStock("FLASH", 10);
    }

    @Test
    void reservationIsActiveUntilJustBeforeItExpires() {
        service.reserve("O-1", "STD", 4);
        clock.advance(Duration.ofMinutes(15).minusNanos(1));
        assertEquals(6, service.available("STD"));
    }

    @Test
    void unitsReturnExactlyWhenReservationExpires() {
        service.reserve("O-1", "STD", 4);
        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.available("STD"));
    }

    @Test
    void eachCategoryExpiresAfterItsOwnPaymentWindow() {
        service.reserve("O-1", "STD", 1);
        service.reserve("O-2", "PRE", 1);
        service.reserve("O-3", "FLASH", 1);

        clock.advance(Duration.ofMinutes(5));
        assertEquals(10, service.available("FLASH"));
        assertEquals(9, service.available("STD"));

        clock.advance(Duration.ofMinutes(10));
        assertEquals(10, service.available("STD"));
        assertEquals(9, service.available("PRE"));

        clock.advance(Duration.ofHours(24));
        assertEquals(10, service.available("PRE"));
    }

    @Test
    void expiredUnitsCanBeReservedByAnotherCustomer() {
        service.reserve("O-1", "STD", 10);
        assertThrows(InsufficientStockException.class, () -> service.reserve("O-2", "STD", 1));

        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.reserve("O-2", "STD", 10).quantity());
    }

    @Test
    void retryAfterExpiryCreatesAFreshReservation() {
        Reservation first = service.reserve("O-1", "STD", 3);
        clock.advance(Duration.ofMinutes(15));

        Reservation retry = service.reserve("O-1", "STD", 3);
        assertNotEquals(first.expiresAt(), retry.expiresAt());
        assertEquals(7, service.available("STD"));
    }

    @Test
    void onlyExpiredReservationsAreReleased() {
        service.reserve("O-1", "STD", 2);
        clock.advance(Duration.ofMinutes(10));
        service.reserve("O-2", "STD", 3);

        clock.advance(Duration.ofMinutes(5));
        assertEquals(7, service.available("STD"));
    }
}
