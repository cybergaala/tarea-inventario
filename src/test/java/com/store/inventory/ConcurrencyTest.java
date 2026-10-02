package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

class ConcurrencyTest {

    private static final int THREADS = 200;
    private static final int STOCK = 50;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private final List<String> alerts = new CopyOnWriteArrayList<>();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> alerts.add(sku));
        service.registerProduct("STD", ProductCategory.STANDARD);
        service.addStock("STD", STOCK);
    }

    @RepeatedTest(20)
    void concurrentOrdersNeverOversell() throws InterruptedException {
        List<Future<Reservation>> results = runConcurrently(i -> () -> service.reserve("O-" + i, "STD", 1));

        int reserved = 0;
        for (Future<Reservation> result : results) {
            try {
                result.get();
                reserved++;
            } catch (ExecutionException e) {
                assertInstanceOf(InsufficientStockException.class, e.getCause());
            }
        }
        assertEquals(STOCK, reserved);
        assertEquals(0, service.available("STD"));
        assertEquals(List.of("STD"), alerts);
    }

    @RepeatedTest(20)
    void concurrentRetriesOfOneOrderReserveOnce() throws Exception {
        List<Future<Reservation>> results = runConcurrently(i -> () -> service.reserve("O-1", "STD", 3));

        Set<Reservation> reservations = results.stream().map(ConcurrencyTest::get).collect(Collectors.toSet());
        assertEquals(1, reservations.size());
        assertEquals(STOCK - 3, service.available("STD"));
    }

    // All tasks wait on one latch so they hit the service at the same moment.
    private <T> List<Future<T>> runConcurrently(IntFunction<Callable<T>> task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREADS; i++) {
                Callable<T> call = task.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
        }
        return futures;
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new AssertionError(e);
        }
    }
}
