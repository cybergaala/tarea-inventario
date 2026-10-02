package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReserveTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.fixed(NOW, ZoneOffset.UTC), (sku, available) -> { });
        service.registerProduct("STD", ProductCategory.STANDARD);
        service.registerProduct("PRE", ProductCategory.PRE_ORDER);
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("STD", 100);
        service.addStock("PRE", 100);
        service.addStock("FLASH", 100);
    }

    @Test
    void reservationExpiresAccordingToCategory() {
        assertEquals(NOW.plus(Duration.ofMinutes(15)), service.reserve("O-1", "STD", 1).expiresAt());
        assertEquals(NOW.plus(Duration.ofHours(24)), service.reserve("O-2", "PRE", 1).expiresAt());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), service.reserve("O-3", "FLASH", 1).expiresAt());
    }

    @Test
    void reservationDescribesTheOrder() {
        Reservation reservation = service.reserve("O-1", "STD", 3);
        assertEquals(new Reservation("O-1", "STD", 3, NOW.plus(Duration.ofMinutes(15))), reservation);
    }

    @Test
    void flashSaleAllowsUpToTwoUnitsPerOrder() {
        service.reserve("O-1", "FLASH", 2);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("O-2", "FLASH", 3));
        assertEquals(98, service.available("FLASH"));
    }

    @Test
    void categoriesWithoutLimitAcceptLargeOrders() {
        service.reserve("O-1", "STD", 100);
        assertEquals(0, service.available("STD"));
    }

    @Test
    void orderLimitIsCheckedBeforeStock() {
        service.registerProduct("FLASH-EMPTY", ProductCategory.FLASH_SALE);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("O-1", "FLASH-EMPTY", 3));
    }

    @Test
    void unknownProductHasNoStock() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("O-1", "UNKNOWN", 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantity(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.reserve("O-1", "STD", quantity));
    }

    @Test
    void rejectsMissingOrderId() {
        assertThrows(IllegalArgumentException.class, () -> service.reserve(" ", "STD", 1));
    }

    @Test
    void retriedOrderReturnsSameReservationWithoutReservingTwice() {
        Reservation first = service.reserve("O-1", "STD", 3);
        Reservation retry = service.reserve("O-1", "STD", 3);
        assertSame(first, retry);
        assertEquals(97, service.available("STD"));
    }

    @Test
    void retrySucceedsEvenWhenStockIsNowExhausted() {
        service.reserve("O-1", "STD", 60);
        service.reserve("O-2", "STD", 40);
        assertEquals(60, service.reserve("O-1", "STD", 60).quantity());
    }

    @Test
    void reusingOrderIdForDifferentRequestIsRejected() {
        service.reserve("O-1", "STD", 3);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("O-1", "STD", 4));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("O-1", "PRE", 3));
        assertEquals(97, service.available("STD"));
        assertEquals(100, service.available("PRE"));
    }
}
