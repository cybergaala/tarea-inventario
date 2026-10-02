package com.store.inventory.stock;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Stock of a single product. All state changes go through this object, which is the
 * per-SKU lock: operations on different products never block each other.
 *
 * <p>Expired reservations are released lazily, at the start of every operation, using the
 * time given by the caller. No background job is needed: a reservation counts as active
 * until its {@code expiresAt}, and never after.
 */
public final class ProductStock {

    private final String sku;
    private final ProductCategory category;
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final PriorityQueue<Reservation> byExpiry =
            new PriorityQueue<>(Comparator.comparing(Reservation::expiresAt));
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

    /**
     * Reserves units for an order. A retry of an order that is already reserved returns the
     * existing reservation instead of reserving twice (the app resends on slow connections).
     */
    public synchronized Reservation reserve(String orderId, int quantity, Instant now, Duration paymentWindow) {
        releaseExpired(now);
        Reservation existing = reservations.get(orderId);
        if (existing != null) {
            if (existing.quantity() != quantity) {
                throw new IllegalArgumentException("Order " + orderId + " already reserved "
                        + existing.quantity() + " units of " + sku + ", requested " + quantity);
            }
            return existing;
        }
        int available = onHand - reserved;
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        Reservation reservation = new Reservation(orderId, sku, quantity, now.plus(paymentWindow));
        reservations.put(orderId, reservation);
        byExpiry.add(reservation);
        reserved += quantity;
        return reservation;
    }

    public synchronized int available(Instant now) {
        releaseExpired(now);
        return onHand - reserved;
    }

    private void releaseExpired(Instant now) {
        while (!byExpiry.isEmpty() && !now.isBefore(byExpiry.peek().expiresAt())) {
            Reservation expired = byExpiry.poll();
            if (reservations.remove(expired.orderId(), expired)) {
                reserved -= expired.quantity();
            }
        }
    }
}
