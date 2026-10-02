package com.store.inventory.alert;

/**
 * Decides when purchasing must hear about low stock: once when a product reaches the threshold,
 * and not again until it is restocked. Not thread-safe; the owning product guards it.
 */
public final class LowStockTracker {

    public static final int THRESHOLD = 5;

    private boolean alerted;

    /** @return true if an alert must be sent for this availability (and marks it as sent) */
    public boolean shouldAlert(int available) {
        if (alerted || available > THRESHOLD) {
            return false;
        }
        alerted = true;
        return true;
    }

    public void restocked() {
        alerted = false;
    }
}
