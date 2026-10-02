package com.store.inventory.stock;

import java.util.Optional;

/**
 * Storage port for product stock. Today it is in memory; the README states it will move to a
 * database shared by several instances, which is why it sits behind an interface.
 */
public interface InventoryStore {

    /**
     * Stores the product unless the SKU already exists.
     *
     * @return the stored product: the given one, or the one that was already there
     */
    ProductStock putIfAbsent(ProductStock product);

    Optional<ProductStock> find(String sku);
}
