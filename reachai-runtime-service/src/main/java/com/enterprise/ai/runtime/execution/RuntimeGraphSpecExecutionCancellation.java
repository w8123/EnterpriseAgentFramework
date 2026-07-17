package com.enterprise.ai.runtime.execution;

import java.util.concurrent.atomic.AtomicBoolean;

/** GraphSpec 执行取消标记；节点之间检查，取消后不再执行后续节点。 */
public final class RuntimeGraphSpecExecutionCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public static RuntimeGraphSpecExecutionCancellation none() {
        return new RuntimeGraphSpecExecutionCancellation();
    }
}
