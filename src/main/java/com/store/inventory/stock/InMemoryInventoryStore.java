package com.store.inventory.stock;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInventoryStore implements InventoryStore {

    private final Map<String, ProductStock> products = new ConcurrentHashMap<>();
    private final Map<String, String> orderSkus = new ConcurrentHashMap<>();

    @Override
    public ProductStock putIfAbsent(ProductStock product) {
        ProductStock existing = products.putIfAbsent(product.sku(), product);
        return existing != null ? existing : product;
    }

    @Override
    public Optional<ProductStock> find(String sku) {
        return Optional.ofNullable(products.get(sku));
    }

    @Override
    public String bindOrder(String orderId, String sku) {
        String existing = orderSkus.putIfAbsent(orderId, sku);
        return existing != null ? existing : sku;
    }

    @Override
    public Optional<String> skuOfOrder(String orderId) {
        return Optional.ofNullable(orderSkus.get(orderId));
    }
}
