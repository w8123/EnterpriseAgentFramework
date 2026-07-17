package com.enterprise.ai.runtime.api;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Transport-level SSE heartbeat（SSE comment）。
 * 不进入 Conversation 事件模型；仅用于静默期发现客户端断开。
 * <p>
 * 使用有界 {@link ScheduledThreadPoolExecutor}（remove-on-cancel），
 * 单个连接的 {@code emitter.send} 阻塞不得拖住其他连接的 heartbeat。
 */
@Component
public class SseHeartbeatSupport implements DisposableBean {

    @FunctionalInterface
    public interface HeartbeatWriter {
        void write(SseEmitter emitter) throws IOException;
    }

    static final HeartbeatWriter COMMENT_WRITER = emitter ->
            emitter.send(SseEmitter.event().comment("heartbeat"));

    private final ScheduledExecutorService scheduler;
    private final HeartbeatWriter writer;
    private final boolean shutdownSchedulerOnDestroy;

    public SseHeartbeatSupport() {
        this(createDefaultScheduler(), COMMENT_WRITER, true);
    }

    /** 包可见：单测注入可控 scheduler / writer。 */
    SseHeartbeatSupport(ScheduledExecutorService scheduler, HeartbeatWriter writer) {
        this(scheduler, writer, false);
    }

    private SseHeartbeatSupport(ScheduledExecutorService scheduler,
                                HeartbeatWriter writer,
                                boolean shutdownSchedulerOnDestroy) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.writer = writer == null ? COMMENT_WRITER : writer;
        this.shutdownSchedulerOnDestroy = shutdownSchedulerOnDestroy;
    }

    static ScheduledThreadPoolExecutor createDefaultScheduler() {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, r -> {
            Thread thread = new Thread(r, "reachai-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 周期性向 emitter 写入 {@code : heartbeat} comment。
     * 写入失败时调用 {@code onWriteFailure}（至多一次），并停止该连接的周期任务。
     */
    public ScheduledFuture<?> start(SseEmitter emitter,
                                    long intervalMs,
                                    Runnable onWriteFailure) {
        Objects.requireNonNull(emitter, "emitter");
        long interval = Math.max(50L, intervalMs);
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicBoolean failureNotified = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (stopped.get()) {
                return;
            }
            try {
                synchronized (emitter) {
                    writer.write(emitter);
                }
            } catch (IOException | RuntimeException ex) {
                stopAndNotify(stopped, failureNotified, futureRef.get(), onWriteFailure);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        futureRef.set(future);
        return future;
    }

    public void stop(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    private static void stopAndNotify(AtomicBoolean stopped,
                                      AtomicBoolean failureNotified,
                                      ScheduledFuture<?> future,
                                      Runnable onWriteFailure) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        if (future != null) {
            future.cancel(false);
        }
        if (onWriteFailure != null && failureNotified.compareAndSet(false, true)) {
            try {
                onWriteFailure.run();
            } catch (Exception ignored) {
                // 取消回调失败不得掩盖断开语义
            }
        }
    }

    @Override
    public void destroy() {
        if (shutdownSchedulerOnDestroy) {
            scheduler.shutdownNow();
        }
    }

    /** 包可见：测试断言调度器状态。 */
    ScheduledExecutorService scheduler() {
        return scheduler;
    }
}
