package com.enterprise.ai.runtime.client.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStreamSubscriptionTest {

    @Test
    void cancelIsIdempotent() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ModelStreamSubscription subscription = new ModelStreamSubscription();
        subscription.attach(closes::incrementAndGet);
        subscription.cancel();
        subscription.cancel();
        subscription.cancel();
        assertEquals(1, closes.get());
        assertTrue(subscription.isCancelled());
    }

    @Test
    void attachAfterCancelClosesImmediately() {
        AtomicInteger closes = new AtomicInteger();
        ModelStreamSubscription subscription = new ModelStreamSubscription();
        subscription.cancel();
        subscription.attach(closes::incrementAndGet);
        assertEquals(1, closes.get());
    }

    @Test
    void cancelAfterAttachClosesOnce() {
        AtomicInteger closes = new AtomicInteger();
        ModelStreamSubscription subscription = new ModelStreamSubscription();
        subscription.attach(closes::incrementAndGet);
        subscription.cancel();
        assertEquals(1, closes.get());
    }

    @Test
    void concurrentCancelAndAttachDoesNotLeak() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        ModelStreamSubscription subscription = new ModelStreamSubscription();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                await(start);
                subscription.attach(closes::incrementAndGet);
                done.countDown();
            });
            pool.submit(() -> {
                await(start);
                subscription.cancel();
                done.countDown();
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(subscription.isCancelled());
        // 每个 attach 的资源最终都必须被关闭（cancel 前 attach 或 cancel 后立即关闭）
        assertEquals(4, closes.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
