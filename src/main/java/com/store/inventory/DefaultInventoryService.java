package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.catalog.CategoryPolicies;
import com.store.inventory.catalog.CategoryPolicy;
import com.store.inventory.stock.InventoryStore;
import com.store.inventory.stock.ProductStock;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;

final class DefaultInventoryService implements InventoryService {

    private static final Logger LOG = System.getLogger(DefaultInventoryService.class.getName());

    private final InventoryStore store;
    private final CategoryPolicies policies;
    private final Clock clock;
    private final StockAlertListener alertListener;

    DefaultInventoryService(InventoryStore store, CategoryPolicies policies, Clock clock,
            StockAlertListener alertListener) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
        this.alertListener = alertListener;
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
        ProductStock product = product(sku);
        product.add(quantity);
        notifyIfLowStock(product);
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
        Reservation reservation = product.reserve(orderId, quantity, clock.instant(), policy.paymentWindow());
        notifyIfLowStock(product);
        return reservation;
    }

    @Override
    public void confirm(String orderId) {
        ProductStock product = (orderId == null ? null : store.skuOfOrder(orderId).flatMap(store::find).orElse(null));
        if (product == null) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
        product.confirm(orderId, clock.instant());
    }

    @Override
    public int available(String sku) {
        return sku == null ? 0 : store.find(sku).map(product -> product.available(clock.instant())).orElse(0);
    }

    // Runs outside the product lock: a slow channel (e.g. email) never blocks reservations.
    private void notifyIfLowStock(ProductStock product) {
        product.claimLowStockAlert(clock.instant()).ifPresent(available -> {
            try {
                alertListener.onLowStock(product.sku(), available);
            } catch (RuntimeException e) {
                // The reservation already happened; a failing channel must not undo it or reach the customer.
                LOG.log(Level.ERROR, "Low stock alert failed for " + product.sku(), e);
                product.releaseLowStockAlert();
            }
        });
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
