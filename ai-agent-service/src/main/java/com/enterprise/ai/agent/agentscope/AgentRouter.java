package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.service.IntentService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.Pipelines;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 路由器 — 基于 AgentDefinition 配置驱动的请求分发
 * <p>
 * 核心改进：路由逻辑完全由 {@link AgentDefinitionService} 中的配置驱动，
 * 新增领域 Agent 只需在管理后台创建 AgentDefinition，无需修改 Java 代码。
 * <p>
 * 职责：
 * 1. 调用 IntentService 进行意图识别
 * 2. 根据意图类型从 AgentDefinitionService 查找对应的 AgentDefinition
 * 3. 通过 AgentFactory 构建 Agent 并执行（单 Agent 或 Pipeline）
 * 4. 将 AgentScope Msg 结果转换为业务层 AgentResult
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final IntentService intentService;
    private final AgentFactory agentFactory;
    private final AgentDefinitionService agentDefinitionService;

    private static final String GENERAL_CHAT = "GENERAL_CHAT";

    /**
     * 路由并执行 Agent 任务
     */
    public AgentResult route(String sessionId, String userId, String message, String intentHint) {
        String intentType = resolveIntent(message, intentHint);
        log.info("[AgentRouter] 路由决策: intent={}, sessionId={}, userId={}", intentType, sessionId, userId);

        Msg input = Msg.builder()
                .textContent(message)
                .build();

        try {
            long startTime = System.currentTimeMillis();

            Msg response = dispatch(intentType, input);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[AgentRouter] 执行完成: intent={}, agent={}, 耗时={}ms",
                    intentType, response.getName(), elapsed);

            return buildResult(true, response, intentType, elapsed);

        } catch (Exception e) {
            log.error("[AgentRouter] Agent执行失败: intent={}", intentType, e);
            return AgentResult.builder()
                    .success(false)
                    .answer("处理过程中遇到异常：" + e.getMessage())
                    .metadata(Map.of("intentType", intentType))
                    .build();
        }
    }

    private String resolveIntent(String message, String intentHint) {
        String raw;
        if (intentHint != null && !intentHint.isBlank()) {
            raw = intentHint;
        } else {
            raw = intentService.recognizeIntent(message);
        }

        // 检查该意图是否有对应的已启用 AgentDefinition
        if (agentDefinitionService.findByIntentType(raw).isEmpty()) {
            log.warn("[AgentRouter] 意图 '{}' 无对应 Agent 定义或已禁用，降级到 {}", raw, GENERAL_CHAT);
            return GENERAL_CHAT;
        }
        return raw;
    }

    /**
     * 配置驱动的路由分发 — 从 AgentDefinitionService 查找定义并构建 Agent
     */
    private Msg dispatch(String intentType, Msg input) {
        AgentDefinition def = agentDefinitionService.findByIntentType(intentType)
                .orElseThrow(() -> new IllegalStateException(
                        "未找到意图 '" + intentType + "' 对应的 Agent 定义"));

        if ("pipeline".equals(def.getType()) && def.getPipelineAgentIds() != null) {
            return executePipeline(def, input);
        } else {
            return executeSingleAgent(agentFactory.buildFromDefinition(def), input);
        }
    }

    private Msg executeSingleAgent(ReActAgent agent, Msg input) {
        log.debug("[AgentRouter] 单Agent执行: {}", agent.getName());
        return agent.call(input).block();
    }

    /**
     * Pipeline 执行 — 按 pipelineAgentIds 顺序构建子 Agent 并串行执行
     */
    private Msg executePipeline(AgentDefinition pipelineDef, Msg input) {
        List<String> agentIds = pipelineDef.getPipelineAgentIds();
        log.debug("[AgentRouter] Pipeline执行: {} -> {} 个子Agent", pipelineDef.getName(), agentIds.size());

        List<AgentBase> agents = agentIds.stream()
                .map(id -> agentDefinitionService.findById(id)
                        .orElseThrow(() -> new IllegalStateException(
                                "Pipeline 子 Agent 不存在: " + id)))
                .map(def -> (AgentBase) agentFactory.buildFromDefinition(def))
                .toList();

        return Pipelines.sequential(agents, input).block();
    }

    private AgentResult buildResult(boolean success, Msg response, String intentType, long elapsed) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("intentType", intentType);
        metadata.put("agentName", response.getName());
        metadata.put("elapsedMs", elapsed);
        if (response.getGenerateReason() != null) {
            metadata.put("generateReason", response.getGenerateReason().name());
        }

        List<String> toolCalls = extractToolCalls(response);
        if (!toolCalls.isEmpty()) {
            metadata.put("toolCalls", toolCalls);
        }

        List<String> steps = extractSteps(response, intentType);
        if (!steps.isEmpty()) {
            metadata.put("steps", steps);
        }

        return AgentResult.builder()
                .success(success)
                .answer(response.getTextContent())
                .metadata(metadata)
                .build();
    }

    private List<String> extractToolCalls(Msg response) {
        List<String> toolCalls = new ArrayList<>();
        if (response.getMetadata() != null) {
            Object calls = response.getMetadata().get("tool_calls");
            if (calls instanceof List<?> list) {
                list.forEach(item -> toolCalls.add(String.valueOf(item)));
            }
        }
        return toolCalls;
    }

    private List<String> extractSteps(Msg response, String intentType) {
        List<String> steps = new ArrayList<>();
        steps.add("意图识别: " + intentType);
        steps.add("Agent执行: " + response.getName());
        if (response.getGenerateReason() != null) {
            steps.add("完成原因: " + response.getGenerateReason().name());
        }
        return steps;
    }
}
