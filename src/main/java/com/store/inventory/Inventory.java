package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.catalog.CategoryPolicies;
import com.store.inventory.stock.InMemoryInventoryStore;
import java.time.Clock;
import java.util.Objects;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(alertListener, "alertListener");
        return new DefaultInventoryService(new InMemoryInventoryStore(), CategoryPolicies.defaults(), clock, alertListener);
    }
}
