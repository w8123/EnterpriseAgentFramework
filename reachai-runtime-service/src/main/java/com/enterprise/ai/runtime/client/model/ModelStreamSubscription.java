package com.enterprise.ai.runtime.client.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型流读取取消句柄。支持注册多个取消动作（Future.cancel + InputStream.close）。
 * cancel 幂等；cancel 后再 attach 立即关闭且不保留引用。
 */
public final class ModelStreamSubscription {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<AutoCloseable> resources = new ArrayList<>();

    public void attach(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        boolean alreadyCancelled;
        synchronized (resources) {
            alreadyCancelled = cancelled.get();
            if (!alreadyCancelled) {
                resources.add(closeable);
            }
        }
        if (alreadyCancelled) {
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

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
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
