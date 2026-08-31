package com.enterprise.ai.runtime.agentscope;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime 强制执行的最终回答阶段。
 * <p>
 * INTERNAL：Supervisor 规划与 Workflow-as-Tool；content.delta 不得公开。<br>
 * PUBLIC_FINAL：下一次模型调用强制 no-tool，content.delta 立即公开。<br>
 * COMPLETED / FAILED / CANCELLED：终态。
 */
public final class AgentScopeAnswerPhase {

    public enum Phase {
        INTERNAL,
        PUBLIC_FINAL,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.INTERNAL);

    public Phase get() {
        return phase.get();
    }

    public boolean isPublicFinal() {
        return phase.get() == Phase.PUBLIC_FINAL;
    }

    public boolean isTerminal() {
        Phase current = phase.get();
        return current == Phase.COMPLETED || current == Phase.FAILED || current == Phase.CANCELLED;
    }

    /**
     * 进入公开最终回答阶段。幂等：已在 PUBLIC_FINAL 或终态时不降级。
     */
    public boolean enterPublicFinal() {
        while (true) {
            Phase current = phase.get();
            if (current == Phase.PUBLIC_FINAL || current == Phase.COMPLETED) {
                return current == Phase.PUBLIC_FINAL;
            }
            if (current == Phase.FAILED || current == Phase.CANCELLED) {
                return false;
            }
            if (phase.compareAndSet(Phase.INTERNAL, Phase.PUBLIC_FINAL)) {
                return true;
            }
        }
    }

    public void markCompleted() {
        phase.compareAndSet(Phase.PUBLIC_FINAL, Phase.COMPLETED);
        phase.compareAndSet(Phase.INTERNAL, Phase.COMPLETED);
    }

    public void markFailed() {
        Phase current = phase.get();
        if (current == Phase.COMPLETED || current == Phase.CANCELLED) {
            return;
        }
        phase.set(Phase.FAILED);
    }

    public void markCancelled() {
        Phase current = phase.get();
        if (current == Phase.COMPLETED) {
            return;
        }
        phase.set(Phase.CANCELLED);
    }
}
