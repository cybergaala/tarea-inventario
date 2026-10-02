package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.catalog.CategoryPolicies;
import com.store.inventory.catalog.CategoryPolicy;
import com.store.inventory.stock.InventoryStore;
import com.store.inventory.stock.ProductStock;
import java.time.Clock;

final class DefaultInventoryService implements InventoryService {

    private final InventoryStore store;
    private final CategoryPolicies policies;
    private final Clock clock;

    DefaultInventoryService(InventoryStore store, CategoryPolicies policies, Clock clock) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireSku(sku);
        if (category == null) {
            throw new IllegalArgumentException("Category is required");
        }
        ProductStock stored = store.putIfAbsent(new ProductStock(sku, category));
        // Registering the same product twice is harmless; changing its category is not.
        if (stored.category() != category) {
            throw new IllegalArgumentException(
                    "Product " + sku + " is already registered as " + stored.category());
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        product(sku).add(quantity);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Order id is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got " + quantity);
        }
        ProductStock product = (sku == null ? null : store.find(sku).orElse(null));
        if (product == null) {
            throw new InsufficientStockException(sku, quantity, 0);
        }
        CategoryPolicy policy = policies.of(product.category());
        if (!policy.allows(quantity)) {
            throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder());
        }
        String boundSku = store.bindOrder(orderId, sku);
        if (!boundSku.equals(sku)) {
            throw new IllegalArgumentException("Order " + orderId + " already reserves " + boundSku);
        }
        return product.reserve(orderId, quantity, clock.instant(), policy.paymentWindow());
    }

    @Override
    public void confirm(String orderId) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public int available(String sku) {
        return sku == null ? 0 : store.find(sku).map(product -> product.available(clock.instant())).orElse(0);
    }

    private ProductStock product(String sku) {
        requireSku(sku);
        return store.find(sku)
                .orElseThrow(() -> new IllegalArgumentException("Product " + sku + " is not registered"));
    }

    private static void requireSku(String sku) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }
    }
}
