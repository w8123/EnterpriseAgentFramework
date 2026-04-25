package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.service.IntentService;
import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.Pipelines;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 路由器 — 基于 AgentDefinition 配置驱动的请求分发
 * <p>
 * 核心改进：路由逻辑完全由 {@link AgentDefinitionService} 中的配置驱动，
 * 新增领域 Agent 只需在管理后台创建 AgentDefinition，无需修改 Java 代码。
 * <p>
 * 职责：
 * 1. 调用 IntentService 进行意图识别
 * 2. 根据意图类型从 AgentDefinitionService 查找对应的 AgentDefinition
 * 3. 生成本次执行的 traceId/ToolExecutionContext，透传给 AgentFactory 用于动态召回与审计
 * 4. 通过 AgentFactory 构建 Agent 并执行（单 Agent 或 Pipeline）
 * 5. 将 AgentScope Msg 结果转换为业务层 AgentResult
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private final IntentService intentService;
    private final AgentFactory agentFactory;
    private final AgentDefinitionService agentDefinitionService;
    private final ToolCallLogService toolCallLogService;

    private static final String GENERAL_CHAT = "GENERAL_CHAT";

    /**
     * 根据显式的 {@link AgentDefinition} 直接执行（跳过意图识别与 keySlug 解析）。
     * <p>
     * Phase 3.0 Agent Studio 发布端点 {@code /api/v1/agents/{key}/chat} 的执行入口：
     * <ol>
     *   <li>{@code AgentVersionService.resolveActiveSnapshot} 已经按 userId 灰度选好了具体版本；</li>
     *   <li>这里直接用传入的 definition 构 Agent 并 call，不再查 intentType；</li>
     *   <li>traceId / ToolExecutionContext 照常生成，保持和 {@link #route} 的审计链路一致。</li>
     * </ol>
     */
    public AgentResult executeByDefinition(AgentDefinition definition, String sessionId,
                                           String userId, String message) {
        return executeByDefinition(definition, sessionId, userId, message, null);
    }

    /**
     * {@link #executeByDefinition(AgentDefinition, String, String, String)} 的 ACL 版本。
     * Phase 3.1 起，网关端点会把 {@code roles} 从 {@code ChatRequest} 取出并注入上下文。
     */
    public AgentResult executeByDefinition(AgentDefinition definition, String sessionId,
                                           String userId, String message, List<String> roles) {
        String traceId = UUID.randomUUID().toString();
        log.info("[AgentRouter] 按定义执行: agent={}, keySlug={}, sessionId={}, roles={}, traceId={}",
                definition.getName(), definition.getKeySlug(), sessionId, roles, traceId);

        Msg input = Msg.builder().textContent(message).build();
        ToolExecutionContext context = ToolExecutionContext.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .agentName(definition.getName())
                .intentType(definition.getIntentType())
                .allowIrreversible(definition.isAllowIrreversible())
                .roles(roles)
                .build();

        long startTime = System.currentTimeMillis();
        try {
            Msg response;
            if ("pipeline".equals(definition.getType()) && definition.getPipelineAgentIds() != null
                    && !definition.getPipelineAgentIds().isEmpty()) {
                response = executePipeline(definition, input, message, context);
            } else {
                response = executeSingleAgent(
                        agentFactory.buildFromDefinition(definition, message, context),
                        input,
                        context);
            }
            long elapsed = System.currentTimeMillis() - startTime;
            return buildResult(true, response, definition.getIntentType(), elapsed, traceId);
        } catch (Exception e) {
            log.error("[AgentRouter] 按定义执行失败: agent={}, traceId={}", definition.getName(), traceId, e);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("traceId", traceId);
            metadata.put("agentName", definition.getName());
            return AgentResult.builder()
                    .success(false)
                    .answer("处理过程中遇到异常：" + e.getMessage())
                    .metadata(metadata)
                    .build();
        }
    }

    /**
     * 路由并执行 Agent 任务
     */
    public AgentResult route(String sessionId, String userId, String message, String intentHint) {
        return route(sessionId, userId, message, intentHint, null);
    }

    /**
     * {@link #route(String, String, String, String)} 的 ACL 版本。
     * Phase 3.1：{@code /api/agent/chat} 的上层 controller 可把 {@code ChatRequest.roles} 传过来。
     */
    public AgentResult route(String sessionId, String userId, String message, String intentHint,
                             List<String> roles) {
        String intentType = resolveIntent(message, intentHint);
        String traceId = UUID.randomUUID().toString();
        log.info("[AgentRouter] 路由决策: intent={}, sessionId={}, userId={}, roles={}, traceId={}",
                intentType, sessionId, userId, roles, traceId);

        Msg input = Msg.builder()
                .textContent(message)
                .build();

        try {
            long startTime = System.currentTimeMillis();

            Msg response = dispatch(intentType, input, sessionId, userId, message, traceId, roles);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[AgentRouter] 执行完成: intent={}, agent={}, 耗时={}ms, traceId={}",
                    intentType, response.getName(), elapsed, traceId);

            return buildResult(true, response, intentType, elapsed, traceId);

        } catch (Exception e) {
            log.error("[AgentRouter] Agent执行失败: intent={}, traceId={}", intentType, traceId, e);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("intentType", intentType);
            metadata.put("traceId", traceId);
            return AgentResult.builder()
                    .success(false)
                    .answer("处理过程中遇到异常：" + e.getMessage())
                    .metadata(metadata)
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
    private Msg dispatch(String intentType, Msg input,
                         String sessionId, String userId, String userMessage, String traceId,
                         List<String> roles) {
        AgentDefinition def = agentDefinitionService.findByIntentType(intentType)
                .orElseThrow(() -> new IllegalStateException(
                        "未找到意图 '" + intentType + "' 对应的 Agent 定义"));

        ToolExecutionContext context = ToolExecutionContext.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .agentName(def.getName())
                .intentType(intentType)
                .allowIrreversible(def.isAllowIrreversible())
                .roles(roles)
                .build();

        if ("pipeline".equals(def.getType()) && def.getPipelineAgentIds() != null) {
            return executePipeline(def, input, userMessage, context);
        } else {
            return executeSingleAgent(
                    agentFactory.buildFromDefinition(def, userMessage, context),
                    input,
                    context);
        }
    }

    private Msg executeSingleAgent(ReActAgent agent, Msg input, ToolExecutionContext ctx) {
        log.debug("[AgentRouter] 单Agent执行: {}", agent.getName());
        ToolExecutionContext prev = ToolExecutionContextHolder.get();
        ToolExecutionContextHolder.set(ctx);
        long t0 = System.currentTimeMillis();
        try {
            Msg response = agent.call(input).block();
            logAgentscopeRun(ctx, agent.getName(), input, response, System.currentTimeMillis() - t0, true, null);
            return response;
        } catch (Exception ex) {
            logAgentscopeRun(ctx, agent.getName(), input, null, System.currentTimeMillis() - t0, false, ex);
            throw ex;
        } finally {
            ToolExecutionContextHolder.set(prev);
        }
    }

    /**
     * Pipeline 执行 — 按 pipelineAgentIds 顺序构建子 Agent 并串行执行。
     * 每个子 Agent 都走动态召回路径，共享同一个 {@link ToolExecutionContext}（traceId 相同）。
     */
    private Msg executePipeline(AgentDefinition pipelineDef, Msg input,
                                String userMessage, ToolExecutionContext context) {
        List<String> agentIds = pipelineDef.getPipelineAgentIds();
        log.debug("[AgentRouter] Pipeline执行: {} -> {} 个子Agent", pipelineDef.getName(), agentIds.size());

        List<AgentBase> agents = agentIds.stream()
                .map(id -> agentDefinitionService.findById(id)
                        .orElseThrow(() -> new IllegalStateException(
                                "Pipeline 子 Agent 不存在: " + id)))
                .map(def -> (AgentBase) agentFactory.buildFromDefinition(def, userMessage, context))
                .toList();

        ToolExecutionContext prev = ToolExecutionContextHolder.get();
        ToolExecutionContextHolder.set(context);
        try {
            return Pipelines.sequential(agents, input).block();
        } finally {
            ToolExecutionContextHolder.set(prev);
        }
    }

    /**
     * 记录一次 AgentScope 顶层调用的入参/出参摘要（与多轮 {@code _trace:llm.stream#n} 互补）。
     */
    private void logAgentscopeRun(ToolExecutionContext ctx,
                                  String reactAgentName,
                                  Msg input,
                                  Msg output,
                                  long elapsedMs,
                                  boolean success,
                                  Exception error) {
        if (toolCallLogService == null || ctx == null || ctx.getTraceId() == null || ctx.getTraceId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("reactAgentName", reactAgentName);
            args.put("userInput", input == null ? null : truncate(input.getTextContent(), 8000));
            Map<String, Object> res = new LinkedHashMap<>();
            if (output != null) {
                res.put("answer", truncate(output.getTextContent(), 12000));
                res.put("msgName", output.getName());
                if (output.getGenerateReason() != null) {
                    res.put("generateReason", output.getGenerateReason().name());
                }
                if (output.getMetadata() != null && !output.getMetadata().isEmpty()) {
                    res.put("metadata", new LinkedHashMap<>(output.getMetadata()));
                }
            }
            if (error != null) {
                res.put("exception", error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            toolCallLogService.record(ctx, "_trace:agentscope.run", args, res, success,
                    error == null ? null : error.getClass().getSimpleName(), elapsedMs, null);
        } catch (Exception ignored) {
            // 与 Tool 审计一致：不影响主链路
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated]";
    }

    private AgentResult buildResult(boolean success, Msg response, String intentType, long elapsed, String traceId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("intentType", intentType);
        metadata.put("agentName", response.getName());
        metadata.put("elapsedMs", elapsed);
        metadata.put("traceId", traceId);
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
