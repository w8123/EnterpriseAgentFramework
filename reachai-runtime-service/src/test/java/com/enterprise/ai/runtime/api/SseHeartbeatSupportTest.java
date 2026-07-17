package com.enterprise.ai.runtime.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseHeartbeatSupportTest {

    private ScheduledThreadPoolExecutor scheduler;
    private SseHeartbeatSupport support;
    private final ConcurrentHashMap<Integer, String> emitterTags = new ConcurrentHashMap<>();

    @AfterEach
    void tearDown() {
        if (support != null) {
            support.destroy();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            assertTrue(scheduler.isShutdown());
            assertEquals(0, scheduler.getQueue().size(), "no residual heartbeat tasks");
        }
        emitterTags.clear();
    }

    @Test
    void writesHeartbeatPeriodicallyAndStopsAfterCancel() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        CountDownLatch twoWrites = new CountDownLatch(2);
        scheduler = SseHeartbeatSupport.createDefaultScheduler();
        support = new SseHeartbeatSupport(scheduler, emitter -> {
            writes.incrementAndGet();
            twoWrites.countDown();
        });

        SseEmitter emitter = new SseEmitter(5_000L);
        ScheduledFuture<?> heartbeat = support.start(emitter, 50L, null);
        assertTrue(twoWrites.await(2, TimeUnit.SECONDS), "must write : heartbeat on schedule");
        int beforeStop = writes.get();
        support.stop(heartbeat);
        assertTrue(heartbeat.isCancelled() || heartbeat.isDone());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        int afterStop = writes.get();
        while (System.nanoTime() < deadline) {
            afterStop = writes.get();
            if (scheduler.getQueue().isEmpty()) {
                break;
            }
            Thread.yield();
        }
        assertEquals(beforeStop, afterStop, "stop must prevent further writes");
        assertTrue(scheduler.getQueue().isEmpty() || heartbeat.isCancelled());
    }

    @Test
    void writeFailureCallbackRunsOnceAndTaskEnds() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        scheduler = SseHeartbeatSupport.createDefaultScheduler();
        support = new SseHeartbeatSupport(scheduler, emitter -> {
            writes.incrementAndGet();
            throw new IOException("Broken pipe");
        });

        SseEmitter emitter = new SseEmitter(5_000L);
        ScheduledFuture<?> heartbeat = support.start(emitter, 50L, () -> {
            failures.incrementAndGet();
            failed.countDown();
        });
        assertTrue(failed.await(2, TimeUnit.SECONDS), "write failure must notify once");
        assertEquals(1, failures.get());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        int writesAtFail = writes.get();
        while (System.nanoTime() < deadline) {
            if (heartbeat.isCancelled() || heartbeat.isDone()) {
                break;
            }
            Thread.yield();
        }
        assertTrue(heartbeat.isCancelled() || heartbeat.isDone(), "task must end after write failure");
        assertEquals(writesAtFail, writes.get(), "no empty wakeups after failure stop");
        assertEquals(1, failures.get(), "failure callback at most once");
    }

    @Test
    void oneBlockingEmitterDoesNotDelayAnother() throws Exception {
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch fastWrote = new CountDownLatch(2);
        List<String> order = new ArrayList<>();
        scheduler = SseHeartbeatSupport.createDefaultScheduler();
        // 按 emitter 身份阻塞；await 不得持有共享锁，否则会伪造成串行
        support = new SseHeartbeatSupport(scheduler, emitter -> {
            String tag = emitterTags.get(System.identityHashCode(emitter));
            if ("blocking".equals(tag)) {
                order.add("block-enter");
                blockerEntered.countDown();
                try {
                    if (!releaseBlocker.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("blocker timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ex);
                }
                order.add("block-exit");
                return;
            }
            order.add("fast");
            fastWrote.countDown();
        });

        SseEmitter blocking = new SseEmitter(5_000L);
        SseEmitter fast = new SseEmitter(5_000L);
        emitterTags.put(System.identityHashCode(blocking), "blocking");
        emitterTags.put(System.identityHashCode(fast), "fast");
        ScheduledFuture<?> hb1 = support.start(blocking, 40L, null);
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS), "blocker heartbeat must start");
        ScheduledFuture<?> hb2 = support.start(fast, 40L, null);
        assertTrue(fastWrote.await(2, TimeUnit.SECONDS),
                "fast emitter must keep writing while other send blocks; order=" + order);
        releaseBlocker.countDown();
        support.stop(hb1);
        support.stop(hb2);
        assertTrue(order.contains("fast"));
    }
}
