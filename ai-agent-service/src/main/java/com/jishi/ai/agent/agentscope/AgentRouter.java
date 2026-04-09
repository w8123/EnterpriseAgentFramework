package com.jishi.ai.agent.agentscope;

import com.jishi.ai.agent.config.LLMConfig;
import com.jishi.ai.agent.model.AgentResult;
import com.jishi.ai.agent.service.IntentService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.Pipelines;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 路由器 — 基于意图分发请求到对应的 AgentScope Agent 或 Pipeline
 * <p>
 * 职责：
 * 1. 调用 IntentService 进行意图识别（Spring AI 路径）
 * 2. 根据意图类型选择单 Agent 或 多 Agent Pipeline
 * 3. 将 AgentScope Msg 结果转换为业务层 AgentResult
 * <p>
 * 编排模式：
 * - 单 Agent：QUERY_DATA / KNOWLEDGE_QA / BUSINESS_OPERATION / GENERAL_CHAT
 * - Pipeline（Sequential）：ANALYSIS（查询 Agent → 分析 Agent）
 * - Pipeline（Sequential）：CREATIVE_TASK（信息采集 Agent → 创意 Agent）
 * - Pipeline（Fanout）：可扩展的并行 Agent 场景
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final IntentService intentService;
    private final AgentFactory agentFactory;
    private final LLMConfig llmConfig;

    /**
     * 路由并执行 Agent 任务
     *
     * @param sessionId  会话ID
     * @param userId     用户标识
     * @param message    用户输入
     * @param intentHint 前端传入的意图提示（可为空）
     * @return Agent 执行结果
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
        if (!llmConfig.getAgents().isEnabled(raw)) {
            log.warn("[AgentRouter] Agent '{}' 已关闭，降级到 GENERAL_CHAT", raw);
            return "GENERAL_CHAT";
        }
        return raw;
    }

    private Msg dispatch(String intentType, Msg input) {
        return switch (intentType) {
            case "QUERY_DATA" -> executeSingleAgent(agentFactory.createQueryDataAgent(), input);
            case "KNOWLEDGE_QA" -> executeSingleAgent(agentFactory.createKnowledgeQAAgent(), input);
            case "BUSINESS_OPERATION" -> executeSingleAgent(agentFactory.createBusinessOperationAgent(), input);
            case "ANALYSIS" -> executeAnalysisPipeline(input);
            case "CREATIVE_TASK" -> executeCreativeTaskPipeline(input);
            default -> executeSingleAgent(agentFactory.createGeneralChatAgent(), input);
        };
    }

    /**
     * 单 Agent 执行
     */
    private Msg executeSingleAgent(ReActAgent agent, Msg input) {
        log.debug("[AgentRouter] 单Agent执行: {}", agent.getName());
        return agent.call(input).block();
    }

    /**
     * 分析 Pipeline（Sequential）：QueryDataAgent → AnalysisAgent
     * <p>
     * 第一个 Agent 负责数据查询，第二个 Agent 基于查询结果进行深度分析。
     * 使用 MultiAgentFormatter 确保上下文在 Agent 间正确传递。
     */
    private Msg executeAnalysisPipeline(Msg input) {
        log.debug("[AgentRouter] 分析Pipeline执行: QueryData → Analysis");
        ReActAgent queryAgent = agentFactory.createMultiAgentQueryDataAgent();
        ReActAgent analysisAgent = agentFactory.createMultiAgentAnalysisAgent();
        return Pipelines.sequential(List.of(queryAgent, analysisAgent), input).block();
    }

    /**
     * 创意任务 Pipeline（Sequential）：InfoGatherAgent → CreativeAgent
     * <p>
     * 第一个 Agent 负责采集所需信息（调用 query_user_profile / query_database 等工具），
     * 第二个 Agent 基于采集到的信息完成创意任务（起名、写文案等）。
     */
    private Msg executeCreativeTaskPipeline(Msg input) {
        log.info("[AgentRouter] 创意任务Pipeline执行: InfoGather → Creative");
        ReActAgent infoGatherAgent = agentFactory.createMultiAgentInfoGatherAgent();
        ReActAgent creativeAgent = agentFactory.createMultiAgentCreativeAgent();
        return Pipelines.sequential(List.of(infoGatherAgent, creativeAgent), input).block();
    }

    private AgentResult buildResult(boolean success, Msg response, String intentType, long elapsed) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("intentType", intentType);
        metadata.put("agentName", response.getName());
        metadata.put("elapsedMs", elapsed);
        if (response.getGenerateReason() != null) {
            metadata.put("generateReason", response.getGenerateReason().name());
        }

        return AgentResult.builder()
                .success(success)
                .answer(response.getTextContent())
                .metadata(metadata)
                .build();
    }
}
