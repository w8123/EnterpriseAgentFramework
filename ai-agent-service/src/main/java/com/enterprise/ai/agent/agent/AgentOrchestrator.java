package com.enterprise.ai.agent.agent;

import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.config.LLMConfig;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.service.IntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 编排器 — 智能体的核心调度中枢
 * <p>
 * 已重构为 AgentScope 架构：
 * - AgentScope ReActAgent 替代原有手动工作流
 * - AgentRouter 负责意图路由和 Pipeline 编排
 * - 保留 AgentWorkflow 作为降级路径（useAgentScope=false 时启用）
 * <p>
 * 遵循 maxSteps 限制，防止无限循环推理（见 ARCHITECTURE.md 第7条）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final AgentRouter agentRouter;
    private final AgentWorkflow agentWorkflow;
    private final IntentService intentService;
    private final LLMConfig llmConfig;

    /**
     * 编排并执行 Agent 任务
     * <p>
     * 默认使用 AgentScope 编排路径。如果 AgentScope 执行失败，
     * 自动降级到原有的 AgentWorkflow 路径。
     */
    public AgentResult orchestrate(String sessionId, String userId, String message, String intentHint) {
        log.info("Agent编排开始: sessionId={}, userId={}", sessionId, userId);

        try {
            AgentResult result = agentRouter.route(sessionId, userId, message, intentHint);
            log.info("Agent编排完成(AgentScope): success={}", result.isSuccess());
            return result;

        } catch (Exception e) {
            log.warn("AgentScope执行异常，降级到传统工作流", e);
            return fallbackToLegacyWorkflow(sessionId, userId, message, intentHint);
        }
    }

    /**
     * 降级路径：使用原有的 AgentWorkflow 执行
     */
    private AgentResult fallbackToLegacyWorkflow(String sessionId, String userId,
                                                  String message, String intentHint) {
        AgentContext context = AgentContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .userMessage(message)
                .maxSteps(llmConfig.getMaxSteps())
                .build();

        try {
            String intentType = (intentHint != null && !intentHint.isBlank())
                    ? intentHint
                    : intentService.recognizeIntent(message);
            context.setIntentType(intentType);

            AgentResult result = switch (intentType) {
                case "KNOWLEDGE_QA" -> agentWorkflow.executeKnowledgeQAFlow(context);
                default -> agentWorkflow.executeGeneralChatFlow(context);
            };

            result.getMetadata().put("intentType", intentType);
            result.getMetadata().put("fallback", true);
            return result;

        } catch (Exception ex) {
            log.error("降级工作流也执行失败", ex);
            return AgentResult.builder()
                    .success(false)
                    .answer("处理过程中遇到异常：" + ex.getMessage())
                    .build();
        }
    }
}
