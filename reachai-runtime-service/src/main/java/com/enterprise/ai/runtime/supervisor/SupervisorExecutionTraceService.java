package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogEntity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupervisorExecutionTraceService {

    private final RuntimeTraceSpanMapper spanMapper;
    private final RuntimeToolCallLogMapper toolLogMapper;
    private final RuntimeGuardDecisionLogMapper guardLogMapper;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final ObjectMapper objectMapper;

    public TraceHandle begin(RuntimeAgentView agent,
                             RuntimeAgentConfigVersionEntity config,
                             List<RuntimeAgentWorkflowToolEntity> workflowTools,
                             Map<String, Object> input) {
        String traceId = firstText(input.get("traceId"), id(32));
        String spanId = id(16);
        LocalDateTime now = LocalDateTime.now();
        RuntimeTraceSpanEntity root = baseSpan(traceId, spanId, null, "SUPERVISOR", agent, config, input);
        root.setStatus("RUNNING");
        root.setInputSummary(limit(text(input.get("message")), 2000));
        root.setMetadataJson(json(Map.of(
                "agentConfigVersionId", config.getId(),
                "agentConfigVersion", config.getVersionNo(),
                "policyProfile", config.getPolicyProfile(),
                "toolCatalogMode", config.getToolCatalogMode())));
        root.setStartedAt(now);
        root.setCreatedAt(now);
        safe(() -> spanMapper.insert(root), "insert Supervisor root span");
        runLifecycleService.beginAgent(traceId, spanId, now, agent, config, workflowTools, input);
        return new TraceHandle(traceId, spanId, root.getId(), now);
    }

    public void plan(TraceHandle trace,
                     RuntimeAgentView agent,
                     RuntimeAgentConfigVersionEntity config,
                     Map<String, Object> input,
                     int planNo,
                     Map<String, Object> plan) {
        LocalDateTime now = LocalDateTime.now();
        RuntimeTraceSpanEntity span = baseSpan(trace.traceId(), id(16), trace.rootSpanId(),
                planNo == 1 ? "PLAN" : "REPLAN", agent, config, input);
        span.setNodeId("supervisor-plan-" + planNo);
        span.setStatus("SUCCESS");
        span.setInputSummary(limit(text(input.get("message")), 2000));
        span.setOutputSummary(limit(json(plan), 4000));
        span.setMetadataJson(json(Map.of("planNo", planNo, "plan", plan)));
        span.setLatencyMs(0);
        span.setStartedAt(now);
        span.setEndedAt(now);
        span.setCreatedAt(now);
        safe(() -> spanMapper.insert(span), "insert plan span");
    }

    public void workflow(TraceHandle trace,
                         RuntimeAgentView agent,
                         RuntimeAgentConfigVersionEntity config,
                         Map<String, Object> input,
                         String toolName,
                         String workflowId,
                         Long workflowVersionId,
                         String workflowVersion,
                         Map<String, Object> args,
                         boolean success,
                         String code,
                         String answer,
                         long elapsedMs,
                         Map<String, Object> resultMetadata) {
        LocalDateTime endedAt = LocalDateTime.now();
        RuntimeTraceSpanEntity span = baseSpan(trace.traceId(), id(16), trace.rootSpanId(),
                "WORKFLOW_TOOL", agent, config, input);
        span.setToolName(toolName);
        span.setNodeId(workflowId);
        span.setStatus(success ? "SUCCESS" : "FAILED");
        span.setInputSummary(limit(json(args), 4000));
        span.setOutputSummary(limit(answer, 4000));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId);
        metadata.put("workflowVersionId", workflowVersionId);
        metadata.put("workflowVersion", workflowVersion);
        if (resultMetadata != null) metadata.put("workflowResult", resultMetadata);
        span.setMetadataJson(json(metadata));
        span.setErrorCode(success ? null : code);
        span.setErrorMessage(success ? null : limit(answer, 2000));
        span.setLatencyMs(toInt(elapsedMs));
        span.setStartedAt(endedAt.minus(elapsedMs, ChronoUnit.MILLIS));
        span.setEndedAt(endedAt);
        span.setCreatedAt(endedAt);
        safe(() -> spanMapper.insert(span), "insert Workflow tool span");
        recordWorkflowNodeSpans(trace, span, agent, config, input, workflowId,
                workflowVersionId, workflowVersion, success, code, resultMetadata, endedAt);

        RuntimeToolCallLogEntity logEntity = new RuntimeToolCallLogEntity();
        logEntity.setTraceId(trace.traceId());
        logEntity.setSessionId(text(input.get("sessionId")));
        logEntity.setUserId(firstText(input.get("userId"), input.get("externalUserId")));
        logEntity.setAgentName(agent.name());
        logEntity.setIntentType(text(input.get("intentHint")));
        logEntity.setProjectId(agent.projectId());
        logEntity.setProjectCode(firstText(input.get("projectCode"), agent.projectCode()));
        logEntity.setEnvironment(firstText(input.get("environment"), "DEV"));
        logEntity.setTenantId(text(input.get("tenantId")));
        logEntity.setAppId(firstText(input.get("appId"), input.get("projectCode"), agent.projectCode()));
        logEntity.setExternalUserId(text(input.get("externalUserId")));
        logEntity.setGlobalUserId(text(input.get("globalUserId")));
        logEntity.setPageInstanceId(text(input.get("pageInstanceId")));
        logEntity.setOrigin(text(input.get("origin")));
        logEntity.setToolName(toolName);
        logEntity.setArgsJson(json(args));
        logEntity.setResultSummary(limit(answer, 4000));
        logEntity.setSuccess(success);
        logEntity.setErrorCode(success ? null : code);
        logEntity.setElapsedMs(toInt(elapsedMs));
        logEntity.setRetrievalTraceJson(json(metadata));
        logEntity.setCreateTime(endedAt);
        safe(() -> toolLogMapper.insert(logEntity), "insert Workflow tool log");
    }

    public void guard(TraceHandle trace,
                      RuntimeAgentView agent,
                      Map<String, Object> input,
                      String targetName,
                      String decision,
                      String reason,
                      Map<String, Object> metadata) {
        RuntimeGuardDecisionLogEntity entity = new RuntimeGuardDecisionLogEntity();
        entity.setTraceId(trace.traceId());
        entity.setProjectId(agent.projectId());
        entity.setProjectCode(firstText(input.get("projectCode"), agent.projectCode()));
        entity.setEnvironment(firstText(input.get("environment"), "DEV"));
        entity.setTenantId(text(input.get("tenantId")));
        entity.setDecisionType("SUPERVISOR_TOOL_POLICY");
        entity.setTargetKind("WORKFLOW_TOOL");
        entity.setTargetName(targetName);
        entity.setDecision(decision);
        entity.setReason(reason);
        entity.setMetadataJson(json(metadata));
        entity.setCreatedAt(LocalDateTime.now());
        safe(() -> guardLogMapper.insert(entity), "insert Supervisor policy decision");
    }

    public void finish(TraceHandle trace, boolean success, String code, String answer, Map<String, Object> metadata) {
        LocalDateTime ended = LocalDateTime.now();
        if (trace.rootId() != null) {
            RuntimeTraceSpanEntity root = spanMapper.selectById(trace.rootId());
            if (root != null) {
                root.setStatus(status(success, code));
                root.setOutputSummary(limit(answer, 4000));
                root.setErrorCode(success ? null : code);
                root.setErrorMessage(success ? null : limit(answer, 2000));
                root.setMetadataJson(json(metadata));
                root.setLatencyMs(toInt(ChronoUnit.MILLIS.between(trace.startedAt(), ended)));
                root.setEndedAt(ended);
                safe(() -> spanMapper.updateById(root), "finish Supervisor root span");
            }
        }
        runLifecycleService.finishAgent(trace.traceId(), success, code, answer, metadata, ended);
    }

    @SuppressWarnings("unchecked")
    private void recordWorkflowNodeSpans(TraceHandle trace,
                                         RuntimeTraceSpanEntity workflowSpan,
                                         RuntimeAgentView agent,
                                         RuntimeAgentConfigVersionEntity config,
                                         Map<String, Object> input,
                                         String workflowId,
                                         Long workflowVersionId,
                                         String workflowVersion,
                                         boolean workflowSuccess,
                                         String workflowCode,
                                         Map<String, Object> resultMetadata,
                                         LocalDateTime endedAt) {
        Object rawSteps = resultMetadata == null ? null : resultMetadata.get("workflowSteps");
        if (!(rawSteps instanceof List<?> steps) || steps.isEmpty()) return;
        for (int index = 0; index < steps.size(); index++) {
            Object rawStep = steps.get(index);
            if (!(rawStep instanceof Map<?, ?> raw)) continue;
            Map<String, Object> step = new LinkedHashMap<>((Map<String, Object>) raw);
            String nodeId = firstText(step.get("nodeId"), step.get("detail"));
            RuntimeTraceSpanEntity child = baseSpan(trace.traceId(), id(16), workflowSpan.getSpanId(),
                    "WORKFLOW_NODE", agent, config, input);
            child.setRuntimeType("LANGGRAPH4J");
            child.setNodeId(nodeId);
            boolean failed = !workflowSuccess && index == steps.size() - 1;
            child.setStatus(failed ? "FAILED" : "SUCCESS");
            Map<String, Object> childMetadata = new LinkedHashMap<>();
            childMetadata.put("workflowId", workflowId);
            childMetadata.put("workflowVersionId", workflowVersionId);
            childMetadata.put("workflowVersion", workflowVersion);
            childMetadata.put("step", step);
            child.setMetadataJson(json(childMetadata));
            child.setErrorCode(failed ? workflowCode : null);
            child.setLatencyMs(0);
            child.setStartedAt(endedAt);
            child.setEndedAt(endedAt);
            child.setCreatedAt(endedAt);
            safe(() -> spanMapper.insert(child), "insert Workflow node span");
        }
    }

    private RuntimeTraceSpanEntity baseSpan(String traceId,
                                             String spanId,
                                             String parentSpanId,
                                             String spanType,
                                             RuntimeAgentView agent,
                                             RuntimeAgentConfigVersionEntity config,
                                             Map<String, Object> input) {
        RuntimeTraceSpanEntity entity = new RuntimeTraceSpanEntity();
        entity.setTraceId(traceId);
        entity.setSpanId(spanId);
        entity.setParentSpanId(parentSpanId);
        entity.setSpanType(spanType);
        entity.setRuntimeType("AGENTSCOPE");
        entity.setAgentId(agent.id());
        entity.setAgentName(agent.name());
        entity.setModelInstanceId(config.getModelInstanceId());
        entity.setProjectCode(firstText(input.get("projectCode"), agent.projectCode()));
        entity.setTenantId(text(input.get("tenantId")));
        entity.setAppId(firstText(input.get("appId"), input.get("projectCode"), agent.projectCode()));
        entity.setExternalUserId(text(input.get("externalUserId")));
        entity.setGlobalUserId(text(input.get("globalUserId")));
        entity.setPageInstanceId(text(input.get("pageInstanceId")));
        return entity;
    }

    private String status(boolean success, String code) {
        if (success) return "SUCCESS";
        if ("SUPERVISOR_CONFIRMATION_REQUIRED".equalsIgnoreCase(code)) return "WAITING_APPROVAL";
        if (code != null && code.toUpperCase().contains("TIMEOUT")) return "TIMEOUT";
        if (code != null && code.toUpperCase().contains("CANCEL")) return "CANCELLED";
        return "FAILED";
    }

    private void safe(Runnable runnable, String action) {
        try {
            runnable.run();
        } catch (Exception ex) {
            log.warn("Failed to {}: {}", action, ex.getMessage());
        }
    }

    private int toInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String id(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null && !text.isBlank()) return text.trim();
        }
        return null;
    }

    private String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record TraceHandle(String traceId, String rootSpanId, Long rootId, LocalDateTime startedAt) {
    }
}
