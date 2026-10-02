package com.store.inventory.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.Inventory;
import com.store.inventory.MutableClock;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.StockAlertListener;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LowStockAlertTest {

    record Alert(String sku, int available) {
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private final List<Alert> alerts = new CopyOnWriteArrayList<>();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> alerts.add(new Alert(sku, available)));
        service.registerProduct("STD", ProductCategory.STANDARD);
        service.addStock("STD", 10);
    }

    @Test
    void noAlertWhileAboveThreshold() {
        service.reserve("O-1", "STD", 4);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void alertsWhenAvailabilityReachesFive() {
        service.reserve("O-1", "STD", 5);
        assertEquals(List.of(new Alert("STD", 5)), alerts);
    }

    @Test
    void alertsWithCurrentAvailabilityWhenDroppingBelowThreshold() {
        service.reserve("O-1", "STD", 8);
        assertEquals(List.of(new Alert("STD", 2)), alerts);
    }

    @Test
    void doesNotRepeatAlertUntilRestock() {
        service.reserve("O-1", "STD", 5);
        service.reserve("O-2", "STD", 2);
        service.reserve("O-3", "STD", 3);
        assertEquals(List.of(new Alert("STD", 5)), alerts);
    }

    @Test
    void expiredReservationsDoNotRearmTheAlert() {
        service.reserve("O-1", "STD", 6);
        clock.advance(Duration.ofMinutes(15));
        service.reserve("O-2", "STD", 6);
        assertEquals(List.of(new Alert("STD", 4)), alerts);
    }

    @Test
    void restockAboveThresholdRearmsTheAlert() {
        service.reserve("O-1", "STD", 6);
        service.addStock("STD", 10);
        service.reserve("O-2", "STD", 10);
        assertEquals(List.of(new Alert("STD", 4), new Alert("STD", 4)), alerts);
    }

    @Test
    void restockThatStaysLowAlertsAgainWithNewAvailability() {
        service.reserve("O-1", "STD", 8);
        service.addStock("STD", 1);
        assertEquals(List.of(new Alert("STD", 2), new Alert("STD", 3)), alerts);
    }

    @Test
    void newProductWithLittleStockAlertsOnFirstStock() {
        service.registerProduct("NEW", ProductCategory.STANDARD);
        service.addStock("NEW", 3);
        assertEquals(List.of(new Alert("NEW", 3)), alerts);
    }

    @Test
    void retriedOrderDoesNotRepeatTheAlert() {
        service.reserve("O-1", "STD", 5);
        service.reserve("O-1", "STD", 5);
        assertEquals(1, alerts.size());
    }

    @Test
    void failingChannelDoesNotBreakTheReservation() {
        StockAlertListener broken = (sku, available) -> {
            throw new IllegalStateException("mail server down");
        };
        InventoryService withBrokenChannel = Inventory.create(clock, broken);
        withBrokenChannel.registerProduct("STD", ProductCategory.STANDARD);
        withBrokenChannel.addStock("STD", 10);

        assertEquals(5, withBrokenChannel.reserve("O-1", "STD", 5).quantity());
        assertEquals(5, withBrokenChannel.available("STD"));
    }

    @Test
    void failedAlertIsRetriedOnNextOperation() {
        List<Alert> delivered = new CopyOnWriteArrayList<>();
        AtomicBoolean down = new AtomicBoolean(true);
        InventoryService withFlakyChannel = Inventory.create(clock, (sku, available) -> {
            if (down.getAndSet(false)) {
                throw new IllegalStateException("mail server down");
            }
            delivered.add(new Alert(sku, available));
        });
        withFlakyChannel.registerProduct("STD", ProductCategory.STANDARD);
        withFlakyChannel.addStock("STD", 10);

        withFlakyChannel.reserve("O-1", "STD", 5);
        withFlakyChannel.reserve("O-2", "STD", 1);
        assertEquals(List.of(new Alert("STD", 4)), delivered);
    }

    @Test
    void productsAreTrackedIndependently() {
        service.registerProduct("OTHER", ProductCategory.STANDARD);
        service.addStock("OTHER", 10);
        service.reserve("O-1", "STD", 5);
        service.reserve("O-2", "OTHER", 5);
        assertEquals(List.of(new Alert("STD", 5), new Alert("OTHER", 5)), alerts);
    }
}
