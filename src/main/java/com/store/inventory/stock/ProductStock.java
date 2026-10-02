package com.store.inventory.stock;

import com.store.inventory.api.ProductCategory;

/**
 * Stock of a single product. All state changes go through this object, which is the
 * per-SKU lock: operations on different products never block each other.
 */
public final class ProductStock {

    private final String sku;
    private final ProductCategory category;
    private int onHand;

    public ProductStock(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    public String sku() {
        return sku;
    }

    public ProductCategory category() {
        return category;
    }

    public synchronized void add(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, got " + quantity);
        }
        onHand = Math.addExact(onHand, quantity);
    }

    public synchronized int available() {
        return onHand;
    }
}
