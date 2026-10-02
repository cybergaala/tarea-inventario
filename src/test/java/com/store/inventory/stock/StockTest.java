package com.store.inventory.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.Inventory;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StockTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void addedStockIsAvailable() {
        service.addStock("SKU-1", 4);
        service.addStock("SKU-1", 6);
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void registeredProductStartsWithoutStock() {
        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void unknownProductHasNoUnits() {
        assertEquals(0, service.available("UNKNOWN"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantity(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", quantity));
    }

    @Test
    void rejectsStockForUnregisteredProduct() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 5));
    }

    @Test
    void registeringTwiceWithSameCategoryKeepsStock() {
        service.addStock("SKU-1", 3);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void rejectsChangingCategoryOfRegisteredProduct() {
        assertThrows(IllegalArgumentException.class,
                () -> service.registerProduct("SKU-1", ProductCategory.FLASH_SALE));
    }

    @Test
    void rejectsMissingSkuOrCategory() {
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct(" ", ProductCategory.STANDARD));
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("SKU-2", null));
    }
}
