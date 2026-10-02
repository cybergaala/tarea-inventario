package com.store.inventory.catalog;

import java.time.Duration;
import java.util.Objects;

/**
 * Business rules attached to a product category: how long the customer has to pay
 * and how many units a single order may reserve.
 */
public record CategoryPolicy(Duration paymentWindow, int maxUnitsPerOrder) {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    public CategoryPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        if (paymentWindow.isNegative() || paymentWindow.isZero()) {
            throw new IllegalArgumentException("paymentWindow must be positive");
        }
        if (maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive");
        }
    }

    public static CategoryPolicy unlimited(Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, UNLIMITED);
    }

    public boolean allows(int quantity) {
        return quantity <= maxUnitsPerOrder;
    }
}
