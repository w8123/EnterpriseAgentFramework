package com.enterprise.ai.runtime.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型流读取句柄。支持注册多个关闭动作（Future.cancel + InputStream.close）。
 * cancel 表示调用方主动中断；closeTransport 表示上游已发送终止事件、只关闭底层连接。
 */
public final class ModelStreamSubscription {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean transportClosed = new AtomicBoolean(false);
    private final List<AutoCloseable> resources = new ArrayList<>();

    public void attach(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        boolean shouldClose;
        synchronized (resources) {
            shouldClose = cancelled.get() || transportClosed.get();
            if (!shouldClose) {
                resources.add(closeable);
            }
        }
        if (shouldClose) {
            // 不持有 resources 锁时关闭，避免外部 close 阻塞取消路径
            closeQuietly(closeable);
        }
    }

    /** 替换主资源（例如从 Future 切换到 InputStream），旧资源若尚未取消则保留一并关闭。 */
    public void replace(AutoCloseable closeable) {
        attach(closeable);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 上游已明确发送 completed 事件，底层 SSE 传输已关闭，但不等同于调用方取消。
     */
    public boolean isTransportClosed() {
        return transportClosed.get();
    }

    /**
     * 在收到上游 completed 后主动关闭可能仍保持的 SSE 连接，避免等待连接空闲超时。
     */
    public void closeTransport() {
        if (!transportClosed.compareAndSet(false, true)) {
            return;
        }
        closeResources();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        closeResources();
    }

    private void closeResources() {
        List<AutoCloseable> snapshot;
        synchronized (resources) {
            snapshot = new ArrayList<>(resources);
            resources.clear();
        }
        for (AutoCloseable resource : snapshot) {
            closeQuietly(resource);
        }
    }

    private static void closeQuietly(AutoCloseable target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception ignored) {
            // dispose 幂等；关闭失败不掩盖取消语义
        }
    }
}
