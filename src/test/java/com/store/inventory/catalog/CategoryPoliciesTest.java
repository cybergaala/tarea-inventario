package com.store.inventory.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CategoryPoliciesTest {

    private final CategoryPolicies policies = CategoryPolicies.defaults();

    // Fails when a new category is added to the enum without defining its rules.
    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void everyCategoryHasAPolicy(ProductCategory category) {
        assertNotNull(policies.of(category));
    }

    @Test
    void defaultsMatchBusinessRules() {
        assertEquals(Duration.ofMinutes(15), policies.of(ProductCategory.STANDARD).paymentWindow());
        assertEquals(Duration.ofHours(24), policies.of(ProductCategory.PRE_ORDER).paymentWindow());
        assertEquals(Duration.ofMinutes(5), policies.of(ProductCategory.FLASH_SALE).paymentWindow());
        assertEquals(CategoryPolicy.UNLIMITED, policies.of(ProductCategory.STANDARD).maxUnitsPerOrder());
        assertEquals(CategoryPolicy.UNLIMITED, policies.of(ProductCategory.PRE_ORDER).maxUnitsPerOrder());
        assertEquals(2, policies.of(ProductCategory.FLASH_SALE).maxUnitsPerOrder());
    }

    @Test
    void flashSaleAllowsUpToTwoUnits() {
        CategoryPolicy flashSale = policies.of(ProductCategory.FLASH_SALE);
        assertTrue(flashSale.allows(2));
        assertFalse(flashSale.allows(3));
    }

    @Test
    void rejectsIncompletePolicyTable() {
        Map<ProductCategory, CategoryPolicy> onlyStandard =
                Map.of(ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)));
        assertThrows(IllegalStateException.class, () -> new CategoryPolicies(onlyStandard));
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new CategoryPolicy(Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new CategoryPolicy(Duration.ofMinutes(1), 0));
    }
}
