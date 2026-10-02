package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.stock.InventoryStore;
import com.store.inventory.stock.ProductStock;

final class DefaultInventoryService implements InventoryService {

    private final InventoryStore store;

    DefaultInventoryService(InventoryStore store) {
        this.store = store;
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
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void confirm(String orderId) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public int available(String sku) {
        return sku == null ? 0 : store.find(sku).map(ProductStock::available).orElse(0);
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
