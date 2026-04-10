package com.enterprise.ai.agent.service;

import com.enterprise.ai.agent.agent.AgentOrchestrator;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Agent服务 — 复杂智能体任务的入口
 * <p>
 * 将用户请求委托给AgentOrchestrator执行完整的意图识别+工作流编排，
 * 并将AgentResult转换为前端可消费的ChatResponse。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentOrchestrator agentOrchestrator;

    /**
     * 执行复杂Agent任务
     */
    public ChatResponse executeAgent(ChatRequest request) {
        log.info("Agent任务开始: userId={}", request.getUserId());

        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        AgentResult agentResult = agentOrchestrator.orchestrate(
                sessionId,
                request.getUserId(),
                request.getMessage(),
                request.getIntentHint()
        );

        return toChatResponse(agentResult);
    }

    /**
     * 获取Agent执行的详细结果（含完整步骤和工具调用链）
     */
    public AgentResult executeAgentDetailed(ChatRequest request) {
        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        return agentOrchestrator.orchestrate(
                sessionId,
                request.getUserId(),
                request.getMessage(),
                request.getIntentHint()
        );
    }

    @SuppressWarnings("unchecked")
    private ChatResponse toChatResponse(AgentResult result) {
        return ChatResponse.builder()
                .answer(result.getAnswer())
                .intentType((String) result.getMetadata().get("intentType"))
                .toolCalls((List<String>) result.getMetadata().get("toolCalls"))
                .reasoningSteps((List<String>) result.getMetadata().get("steps"))
                .metadata(result.getMetadata())
                .build();
    }
}
