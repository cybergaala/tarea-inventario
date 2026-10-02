package com.store.inventory.stock;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInventoryStore implements InventoryStore {

    private final Map<String, ProductStock> products = new ConcurrentHashMap<>();

    @Override
    public ProductStock putIfAbsent(ProductStock product) {
        ProductStock existing = products.putIfAbsent(product.sku(), product);
        return existing != null ? existing : product;
    }

    @Override
    public Optional<ProductStock> find(String sku) {
        return Optional.ofNullable(products.get(sku));
    }
}
