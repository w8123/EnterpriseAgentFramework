package com.enterprise.ai.agent.service;

import com.enterprise.ai.agent.agent.AgentOrchestrator;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
    private final InteractiveFormResumeService interactiveFormResumeService;

    /**
     * 执行复杂Agent任务
     */
    public ChatResponse executeAgent(ChatRequest request) {
        log.info("Agent任务开始: userId={}", request.getUserId());

        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        AgentResult agentResult = resolveAgentResult(request, sessionId);
        return toChatResponse(agentResult, sessionId);
    }

    /**
     * 获取Agent执行的详细结果（含完整步骤和工具调用链）
     */
    public AgentResult executeAgentDetailed(ChatRequest request) {
        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : UUID.randomUUID().toString();

        return resolveAgentResult(request, sessionId);
    }

    private AgentResult resolveAgentResult(ChatRequest request, String sessionId) {
        boolean hasInteraction = request.getInteractionId() != null && !request.getInteractionId().isBlank();
        boolean hasMessage = request.getMessage() != null && !request.getMessage().isBlank();
        if (!hasInteraction && !hasMessage) {
            return AgentResult.builder()
                    .success(false)
                    .answer("请提供 message，或提供 interactionId + uiSubmit 以继续交互。")
                    .build();
        }
        if (hasInteraction) {
            return interactiveFormResumeService.resume(request, sessionId);
        }
        return agentOrchestrator.orchestrate(
                sessionId,
                request.getUserId(),
                request.getMessage(),
                request.getIntentHint(),
                request.getRoles()
        );
    }

    @SuppressWarnings("unchecked")
    private ChatResponse toChatResponse(AgentResult result, String sessionId) {
        Map<String, Object> meta = result.getMetadata();
        return ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.getAnswer())
                .intentType(meta != null ? (String) meta.get("intentType") : null)
                .toolCalls(meta != null ? (List<String>) meta.get("toolCalls") : null)
                .reasoningSteps(meta != null ? (List<String>) meta.get("steps") : null)
                .metadata(meta)
                .uiRequest(result.getUiRequest())
                .build();
    }
}
