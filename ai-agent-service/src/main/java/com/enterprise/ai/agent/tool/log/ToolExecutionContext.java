package com.enterprise.ai.agent.tool.log;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨一次 Agent 执行共享的调用上下文：sessionId / userId / agentName / traceId
 * 以及本次召回的原始 trace（top-K JSON）。
 * <p>
 * 由 {@code AgentRouter} 构建 → {@code AgentFactory#buildFromDefinition(...)} 在召回完成后回填
 * {@code retrievalTraceJson} → 注入每个 {@code AiToolAgentAdapter}，
 * 再由 {@code ToolCallLogService} 落库，供 Phase 2 Skill Mining 使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionContext {

    private String traceId;

    private String sessionId;

    private String userId;

    private String agentName;

    private String intentType;

    /** 本次 Agent 召回 top-K + 分数 + 选中项序列化 JSON；在召回完成后由 AgentFactory 回填。 */
    private String retrievalTraceJson;
}
