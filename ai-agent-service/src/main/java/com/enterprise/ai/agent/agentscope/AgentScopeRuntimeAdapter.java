package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.model.interactive.UiRequestPayload;
import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.AgentRuntimeCapability;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.skill.ToolExecutionContextHolder;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tool.log.ToolExecutionContext;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.pipeline.Pipelines;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentScopeRuntimeAdapter implements AgentRuntimeAdapter {

    private static final String RUNTIME_TYPE = DEFAULT_RUNTIME_TYPE;

    private final AgentFactory agentFactory;
    private final AgentEntryService agentEntryService;
    private final ObjectMapper objectMapper;
    private final ToolCallLogService toolCallLogService;

    @Override
    public String runtimeType() {
        return RUNTIME_TYPE;
    }

    @Override
    public AgentRuntimeCapability capability() {
        return AgentRuntimeCapability.builder()
                .runtimeType(RUNTIME_TYPE)
                .displayName("AgentScope")
                .agentMode("AUTONOMOUS")
                .configurationSurface("FORM")
                .primaryAction("配置对话能力")
                .resourcePolicy("AGENT_DEFAULTS")
                .description("当前默认对话运行时，支持 ReActAgent、Toolkit 与 Pipeline 兼容执行。")
                .available(true)
                .supportedModelType("LLM")
                .supportsStreaming(true)
                .supportsTools(true)
                .supportsHandoff(false)
                .supportsGraph(false)
                .supportsHumanInterrupt(true)
                .supportsArtifacts(false)
                .supportsCodeWorkspace(false)
                .supportsCloudExecution(false)
                .securityLevel("PLATFORM")
                .build();
    }

    @Override
    public boolean supports(AgentRuntimeRequest request) {
        return request != null && request.getAgentRuntimeProfile() != null;
    }

    @Override
    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        AgentRuntimeProfile profile = request.getAgentRuntimeProfile();
        String traceId = request.getTraceId();
        Msg input = Msg.builder().textContent(request.getMessage()).build();
        ToolExecutionContext context = ToolExecutionContext.builder()
                .traceId(traceId)
                .sessionId(request.getSessionId())
                .userId(request.getUserId())
                .agentName(profile.getName())
                .agentId(profile.getId())
                .intentType(request.getIntentType())
                .projectCode(profile.getProjectCode())
                .allowIrreversible(profile.isAllowIrreversible())
                .roles(request.getRoles())
                .currentTurnMessage(request.getMessage())
                .build();

        long startTime = System.currentTimeMillis();
        try {
            Msg response;
            if ("pipeline".equals(profile.getType()) && profile.getPipelineAgentIds() != null
                    && !profile.getPipelineAgentIds().isEmpty()) {
                response = executePipeline(profile, input, request.getMessage(), context);
            } else {
                response = executeSingleAgent(
                        agentFactory.buildFromProfile(profile, request.getMessage(), context),
                        input,
                        context);
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[AgentScopeRuntime] 执行完成: agent={}, elapsedMs={}, traceId={}",
                    profile.getName(), elapsed, traceId);
            return buildResult(true, response, request.getIntentType(), elapsed, traceId, context, null);
        } catch (Exception e) {
            log.error("[AgentScopeRuntime] 执行失败: agent={}, traceId={}", profile.getName(), traceId, e);
            long elapsed = System.currentTimeMillis() - startTime;
            return AgentRuntimeResult.builder()
                    .success(false)
                    .answer("处理过程中遇到异常：" + e.getMessage())
                    .runtimeType(RUNTIME_TYPE)
                    .traceId(traceId)
                    .agentName(profile.getName())
                    .errorCode(e.getClass().getSimpleName())
                    .errorMessage(e.getMessage())
                    .metadata(Map.of(
                            "traceId", traceId,
                            "agentName", profile.getName(),
                            "runtimeType", RUNTIME_TYPE,
                            "elapsedMs", elapsed))
                    .build();
        }
    }

    private Msg executeSingleAgent(ReActAgent agent, Msg input, ToolExecutionContext ctx) {
        log.debug("[AgentScopeRuntime] 单 Agent 执行: {}", agent.getName());
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

    private Msg executePipeline(AgentRuntimeProfile pipelineProfile, Msg input,
                                String userMessage, ToolExecutionContext context) {
        List<String> agentIds = pipelineProfile.getPipelineAgentIds();
        log.debug("[AgentScopeRuntime] Pipeline 执行: {} -> {} 个子 Agent", pipelineProfile.getName(), agentIds.size());

        List<AgentBase> agents = agentIds.stream()
                .map(id -> agentEntryService.findById(id)
                        .orElseThrow(() -> new IllegalStateException("Pipeline 子 Agent 不存在: " + id)))
                .map(entry -> (AgentBase) agentFactory.buildFromProfile(
                        AgentRuntimeProfile.fromAgentEntry(entry, objectMapper), userMessage, context))
                .toList();

        ToolExecutionContext prev = ToolExecutionContextHolder.get();
        ToolExecutionContextHolder.set(context);
        try {
            return Pipelines.sequential(agents, input).block();
        } finally {
            ToolExecutionContextHolder.set(prev);
        }
    }

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
            args.put("runtimeType", RUNTIME_TYPE);
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
            toolCallLogService.record(ctx, "runtime.agent.run", args, res, success,
                    error == null ? null : error.getClass().getSimpleName(), elapsedMs, null);
        } catch (Exception ignored) {
            // 审计不影响主链路
        }
    }

    private AgentRuntimeResult buildResult(boolean success, Msg response, String intentType, long elapsed,
                                           String traceId, ToolExecutionContext execCtx, Exception error) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intentType", intentType);
        metadata.put("agentName", response.getName());
        metadata.put("elapsedMs", elapsed);
        metadata.put("traceId", traceId);
        metadata.put("runtimeType", RUNTIME_TYPE);
        if (response.getGenerateReason() != null) {
            metadata.put("generateReason", response.getGenerateReason().name());
        }

        List<String> toolCalls = extractToolCalls(response);
        if (!toolCalls.isEmpty()) {
            metadata.put("toolCalls", toolCalls);
        }

        List<String> steps = extractSteps(response, intentType);
        UiRequestPayload pending = execCtx == null ? null : execCtx.getPendingUiRequest();
        if (pending != null) {
            metadata.put("uiRequest", pending);
        }

        return AgentRuntimeResult.builder()
                .success(success)
                .answer(response.getTextContent())
                .runtimeType(RUNTIME_TYPE)
                .traceId(traceId)
                .agentName(response.getName())
                .toolCalls(toolCalls)
                .steps(steps)
                .uiRequest(pending)
                .metadata(metadata)
                .errorCode(error == null ? null : error.getClass().getSimpleName())
                .errorMessage(error == null ? null : error.getMessage())
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
        steps.add("Runtime: " + RUNTIME_TYPE);
        steps.add("Agent执行: " + response.getName());
        if (response.getGenerateReason() != null) {
            steps.add("完成原因: " + response.getGenerateReason().name());
        }
        return steps;
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
}
