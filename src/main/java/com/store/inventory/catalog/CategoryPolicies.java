package com.store.inventory.catalog;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Single place where category rules are defined. Adding a category to {@link ProductCategory}
 * requires adding its policy here; {@link #defaults()} fails fast if one is missing.
 */
public final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    public CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        EnumMap<ProductCategory, CategoryPolicy> copy = new EnumMap<>(ProductCategory.class);
        copy.putAll(policies);
        for (ProductCategory category : ProductCategory.values()) {
            if (!copy.containsKey(category)) {
                throw new IllegalStateException("Missing policy for category " + category);
            }
        }
        this.policies = copy;
    }

    public static CategoryPolicies defaults() {
        return new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)),
                // Paid by bank transfer, which takes longer to clear.
                ProductCategory.PRE_ORDER, CategoryPolicy.unlimited(Duration.ofHours(24)),
                ProductCategory.FLASH_SALE, new CategoryPolicy(Duration.ofMinutes(5), 2)));
    }

    public CategoryPolicy of(ProductCategory category) {
        return policies.get(category);
    }
}
