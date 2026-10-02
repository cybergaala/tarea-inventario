package com.store.inventory.stock;

import com.store.inventory.alert.LowStockTracker;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
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
    // ponytail: kept forever so late retries of paid orders are recognized; move to the DB with a TTL.
    private final Map<String, Reservation> confirmed = new HashMap<>();
    private final PriorityQueue<Reservation> byExpiry =
            new PriorityQueue<>(Comparator.comparing(Reservation::expiresAt));
    private final LowStockTracker lowStock = new LowStockTracker();
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
        lowStock.restocked();
    }

    /**
     * Reserves units for an order. A retry of an order that is already reserved returns the
     * existing reservation instead of reserving twice (the app resends on slow connections).
     */
    public synchronized Reservation reserve(String orderId, int quantity, Instant now, Duration paymentWindow) {
        releaseExpired(now);
        Reservation existing = reservations.getOrDefault(orderId, confirmed.get(orderId));
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

    /**
     * Turns an active reservation into a sale: its units leave the warehouse for good.
     *
     * @throws IllegalStateException if the order has no active reservation (unknown, expired or already confirmed)
     */
    public synchronized void confirm(String orderId, Instant now) {
        releaseExpired(now);
        Reservation reservation = reservations.remove(orderId);
        if (reservation == null) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
        reserved -= reservation.quantity();
        onHand -= reservation.quantity();
        confirmed.put(orderId, reservation);
    }

    public synchronized int available(Instant now) {
        releaseExpired(now);
        return onHand - reserved;
    }

    /**
     * Claims the low stock alert for the current availability, if one is due. Only one caller
     * ever gets it until the next restock, so the alert can be sent outside this lock.
     */
    public synchronized OptionalInt claimLowStockAlert(Instant now) {
        int available = available(now);
        return lowStock.shouldAlert(available) ? OptionalInt.of(available) : OptionalInt.empty();
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
