package com.enterprise.ai.agent.tool.log;

import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /**
     * 是否允许调用 IRREVERSIBLE 副作用 Tool（护栏白名单）。
     * 来源于 {@link com.enterprise.ai.agent.agent.AgentDefinition#isAllowIrreversible()}；
     * 默认 false，Phase 3.0 起 {@code AiToolAgentAdapter} 会在执行前检查。
     */
    @Builder.Default
    private boolean allowIrreversible = false;

    /**
     * 调用者的角色编码列表（Phase 3.1 Tool ACL）。
     * <p>
     * 来源：{@link com.enterprise.ai.agent.model.ChatRequest#getRoles()} 或网关端点从 JWT / 请求头解出的身份。
     * <p>
     * 若为空，{@code AgentFactory.createToolkit} 会跳过 ACL 校验，走旧行为并打一条 warn，
     * 方便灰度期不破坏现有 Agent 调用；正式接入后所有生产请求都应该带至少一个角色。
     */
    private List<String> roles;

    /**
     * 当前用户轮次的自然语言输入（由 AgentRouter 注入），供 InteractiveFormSkill 槽抽取使用。
     */
    private String currentTurnMessage;

    /**
     * 当某 Tool/Skill 挂起交互时，由 {@link com.enterprise.ai.agent.agentscope.adapter.AiToolAgentAdapter} 回填，
     * AgentRouter 在构建 {@link com.enterprise.ai.agent.model.AgentResult} 时读取。
     */
    private UiRequestPayload pendingUiRequest;
}

