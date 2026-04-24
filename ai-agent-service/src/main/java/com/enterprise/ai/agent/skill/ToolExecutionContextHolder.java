package com.enterprise.ai.agent.skill;

import com.enterprise.ai.agent.tool.log.ToolExecutionContext;

/**
 * 把当前正在执行的 {@link ToolExecutionContext} 暴露给 Skill 内部使用。
 * <p>
 * 适配器在调用 {@code aiTool.execute(args)} 前 set，调用后 restore；
 * Skill 的 Executor 读取它来构造子 Agent 的审计上下文（复用同一个 traceId）。
 * <p>
 * 使用约束：因 AgentScope 通过 {@code Mono.fromCallable} 将 tool 调用序列化到单线程回调，
 * ThreadLocal 在 {@code invoke()} 范围内是可靠的。
 */
public final class ToolExecutionContextHolder {

    private static final ThreadLocal<ToolExecutionContext> CURRENT = new ThreadLocal<>();

    private ToolExecutionContextHolder() {}

    public static void set(ToolExecutionContext ctx) {
        if (ctx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(ctx);
        }
    }

    public static ToolExecutionContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
