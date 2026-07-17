package com.enterprise.ai.runtime.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 执行请求级取消。Controller / Adapter / ChatModel / HTTP 流共享同一实例。
 * cancel 幂等；注册回调前已取消时回调立即执行。
 */
public final class RuntimeAgentExecutionCancellation {

    public static final RuntimeAgentExecutionCancellation NOOP = new RuntimeAgentExecutionCancellation(true);

    private final boolean noop;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> listeners = new ArrayList<>();

    public RuntimeAgentExecutionCancellation() {
        this(false);
    }

    private RuntimeAgentExecutionCancellation(boolean noop) {
        this.noop = noop;
    }

    public boolean isCancelled() {
        return !noop && cancelled.get();
    }

    public void cancel() {
        if (noop) {
            return;
        }
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        List<Runnable> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable listener : snapshot) {
            runQuietly(listener);
        }
    }

    public void onCancel(Runnable action) {
        if (noop || action == null) {
            return;
        }
        if (cancelled.get()) {
            runQuietly(action);
            return;
        }
        synchronized (listeners) {
            if (cancelled.get()) {
                runQuietly(action);
                return;
            }
            listeners.add(action);
        }
    }

    public void throwIfCancelled() {
        if (!noop && cancelled.get()) {
            throw new CancellationSignal();
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 取消回调失败不得掩盖取消语义
        }
    }

    /** 内部取消信号；不得触发 sync fallback 或 forced PUBLIC_FINAL。 */
    public static final class CancellationSignal extends RuntimeException {
        public CancellationSignal() {
            super("SUPERVISOR_CANCELLED");
        }
    }
}
