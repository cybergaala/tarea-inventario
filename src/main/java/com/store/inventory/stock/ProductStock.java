package com.store.inventory.stock;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Stock of a single product. All state changes go through this object, which is the
 * per-SKU lock: operations on different products never block each other.
 */
public final class ProductStock {

    private final String sku;
    private final ProductCategory category;
    private final Map<String, Reservation> reservations = new HashMap<>();
    private int onHand;
    private int reserved;

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

    public synchronized Reservation reserve(String orderId, int quantity, Instant expiresAt) {
        int available = available();
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        Reservation reservation = new Reservation(orderId, sku, quantity, expiresAt);
        reservations.put(orderId, reservation);
        reserved += quantity;
        return reservation;
    }

    public synchronized int available() {
        return onHand - reserved;
    }
}
