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

    /**
     * Links an order to the product it reserves, unless the order is already linked.
     *
     * @return the SKU the order is linked to: the given one, or the one it already had
     */
    String bindOrder(String orderId, String sku);

    Optional<String> skuOfOrder(String orderId);
}
