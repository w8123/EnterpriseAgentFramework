package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogEntity;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.execution.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /**
     * Closes every still-waiting span plus the root RunOps row for an expired interaction.
     * Called only after the interaction-session CAS succeeds, inside the same transaction.
     */
    public void expireWaitingInteraction(String traceId,
                                         String interactionId,
                                         LocalDateTime expiredAt) {
        if (!StringUtils.hasText(traceId)) {
            return;
        }
        LocalDateTime endedAt = expiredAt == null ? LocalDateTime.now() : expiredAt;
        spanMapper.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                .eq(RuntimeTraceSpanEntity::getTraceId, traceId.trim())
                .in(RuntimeTraceSpanEntity::getStatus, List.of("WAITING_USER", "WAITING_APPROVAL"))
                .isNull(RuntimeTraceSpanEntity::getEndedAt)
                .set(RuntimeTraceSpanEntity::getStatus, "TIMEOUT")
                .set(RuntimeTraceSpanEntity::getErrorCode, "RUNTIME_INTERACTION_EXPIRED")
                .set(RuntimeTraceSpanEntity::getErrorMessage, "Interaction expired: " + interactionId)
                .set(RuntimeTraceSpanEntity::getEndedAt, endedAt));
        runLifecycleService.expireWaitingInteraction(traceId, interactionId, endedAt);
    }

    public TraceHandle begin(RuntimeAgentView agent,
                             RuntimeAgentConfigVersionEntity config,
                             List<RuntimeAgentWorkflowToolEntity> workflowTools,
                             Map<String, Object> input) {
        return begin(agent, config, workflowTools, input, null);
    }

    public TraceHandle begin(RuntimeAgentView agent,
                             RuntimeAgentConfigVersionEntity config,
                             List<RuntimeAgentWorkflowToolEntity> workflowTools,
                             Map<String, Object> input,
                             WorkflowExecutionIdentity identity) {
        String traceId = firstText(input.get("traceId"), id(32));
        String spanId = id(16);
        LocalDateTime now = LocalDateTime.now();
        RuntimeTraceSpanEntity root = baseSpan(traceId, spanId, null, "SUPERVISOR", agent, config, input);
        root.setStatus("RUNNING");
        root.setInputSummary(json(WorkflowTraceSanitizer.sanitizeInputSummary(input)));
        root.setMetadataJson(json(Map.of(
                "agentConfigVersionId", config.getId(),
                "agentConfigVersion", config.getVersionNo(),
                "policyProfile", config.getPolicyProfile(),
                "toolCatalogMode", config.getToolCatalogMode())));
        root.setStartedAt(now);
        root.setCreatedAt(now);
        safe(() -> spanMapper.insert(root), "insert Supervisor root span");
        runLifecycleService.beginAgent(traceId, spanId, now, agent, config, workflowTools, input, identity);
        return new TraceHandle(traceId, spanId, root.getId(), now);
    }

    /**
     * Continues an existing Supervisor root span/run for the same traceId after WAITING_USER.
     * Returns null when no reusable root exists (caller should {@link #begin}).
     */
    public TraceHandle resume(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        String id = traceId.trim();
        RuntimeTraceSpanEntity root = spanMapper.selectOne(Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getTraceId, id)
                .eq(RuntimeTraceSpanEntity::getSpanType, "SUPERVISOR")
                .isNull(RuntimeTraceSpanEntity::getParentSpanId)
                .orderByAsc(RuntimeTraceSpanEntity::getId)
                .last("LIMIT 1"));
        if (root == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        root.setStatus("RUNNING");
        root.setEndedAt(null);
        root.setErrorCode(null);
        root.setErrorMessage(null);
        safe(() -> spanMapper.updateById(root), "resume Supervisor root span");
        runLifecycleService.resumeAgent(id);
        LocalDateTime startedAt = root.getStartedAt() == null ? now : root.getStartedAt();
        return new TraceHandle(id, root.getSpanId(), root.getId(), startedAt);
    }

    public TraceHandle beginOrResume(RuntimeAgentView agent,
                                     RuntimeAgentConfigVersionEntity config,
                                     List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                     Map<String, Object> input) {
        return beginOrResume(agent, config, workflowTools, input, null);
    }

    public TraceHandle beginOrResume(RuntimeAgentView agent,
                                     RuntimeAgentConfigVersionEntity config,
                                     List<RuntimeAgentWorkflowToolEntity> workflowTools,
                                     Map<String, Object> input,
                                     WorkflowExecutionIdentity identity) {
        String resumeTraceId = firstText(input == null ? null : input.get("traceId"));
        Object continuation = input == null ? null : input.get("__supervisorContinuation");
        if (continuation instanceof Map<?, ?> map) {
            resumeTraceId = firstText(map.get("traceId"), resumeTraceId);
        }
        if (Boolean.TRUE.equals(input == null ? null : input.get("__resumeExistingTrace"))
                && StringUtils.hasText(resumeTraceId)) {
            TraceHandle resumed = resume(resumeTraceId);
            if (resumed != null) {
                return resumed;
            }
        }
        return begin(agent, config, workflowTools, input, identity);
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
        Map<String, Object> safePlan = WorkflowTraceSanitizer.sanitizePlanSummary(planNo, plan);
        span.setNodeId("supervisor-plan-" + planNo);
        span.setStatus("SUCCESS");
        span.setInputSummary(json(WorkflowTraceSanitizer.sanitizeInputSummary(input)));
        span.setOutputSummary(limit(json(safePlan), 4000));
        span.setMetadataJson(json(safePlan));
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
        workflow(trace, agent, config, input, toolName, workflowId, workflowVersionId, workflowVersion,
                args, success, code, answer, elapsedMs, resultMetadata, null);
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
                         Map<String, Object> resultMetadata,
                         WorkflowExecutionIdentity identity) {
        LocalDateTime endedAt = LocalDateTime.now();
        RuntimeTraceSpanEntity span = baseSpan(trace.traceId(), id(16), trace.rootSpanId(),
                "WORKFLOW_TOOL", agent, config, input);
        boolean waiting = WorkflowInteractionCodes.WAITING.equals(code)
                || "WAITING_USER".equalsIgnoreCase(code)
                || "RUNTIME_INTERACTION_WAITING".equals(code);
        Map<String, Object> safeResult = WorkflowTraceSanitizer.sanitizeWorkflowResult(resultMetadata);
        boolean businessTerminal = "BUSINESS_TERMINAL".equalsIgnoreCase(
                firstText(safeResult.get("outcomeClass")));
        Map<String, Object> safeArgs = WorkflowTraceSanitizer.sanitizeArgs(args);
        String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
        span.setToolName(toolName);
        span.setNodeId(workflowId);
        span.setStatus(waiting
                ? "WAITING_USER"
                : (businessTerminal ? "BUSINESS_TERMINAL" : (success ? "SUCCESS" : "FAILED")));
        span.setInputSummary(limit(json(safeArgs), 4000));
        span.setOutputSummary(limit(safeAnswer, 4000));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowId", workflowId);
        metadata.put("workflowVersionId", workflowVersionId);
        metadata.put("workflowVersion", workflowVersion);
        metadata.put("workflowResult", safeResult);
        span.setMetadataJson(json(metadata));
        span.setErrorCode(success || waiting || businessTerminal ? null : code);
        span.setErrorMessage(success || waiting || businessTerminal ? null : limit(safeAnswer, 2000));
        span.setLatencyMs(toInt(elapsedMs));
        span.setStartedAt(endedAt.minus(elapsedMs, ChronoUnit.MILLIS));
        span.setEndedAt(waiting ? null : endedAt);
        span.setCreatedAt(endedAt);
        safe(() -> spanMapper.insert(span), "insert Workflow tool span");
        recordWorkflowNodeSpans(trace, span, agent, config, input, workflowId,
                workflowVersionId, workflowVersion, success, code, safeResult, endedAt);

        RuntimeToolCallLogEntity logEntity = new RuntimeToolCallLogEntity();
        logEntity.setTraceId(trace.traceId());
        logEntity.setSessionId(text(input.get("sessionId")));
        logEntity.setUserId(identity != null && identity.userTrusted() ? identity.userId() : null);
        logEntity.setAgentName(agent.name());
        logEntity.setIntentType(text(input.get("intentHint")));
        logEntity.setProjectId(agent.projectId());
        logEntity.setProjectCode(firstText(agent.projectCode(), input.get("projectCode")));
        logEntity.setEnvironment(firstText(input.get("environment"), "DEV"));
        logEntity.setTenantId(text(input.get("tenantId")));
        logEntity.setAppId(firstText(input.get("appId"), agent.projectCode(), input.get("projectCode")));
        logEntity.setExternalUserId(identity != null && identity.userTrusted() ? identity.userId() : null);
        logEntity.setGlobalUserId(identity != null && identity.userTrusted() ? identity.userId() : null);
        logEntity.setPageInstanceId(text(input.get("pageInstanceId")));
        logEntity.setOrigin(text(input.get("origin")));
        logEntity.setToolName(toolName);
        logEntity.setArgsJson(json(safeArgs));
        logEntity.setResultSummary(limit(safeAnswer, 4000));
        logEntity.setSuccess(success);
        logEntity.setErrorCode(success || waiting ? null : code);
        logEntity.setElapsedMs(toInt(elapsedMs));
        // Never persist full workflow payload / retrieval content.
        logEntity.setRetrievalTraceJson(json(Map.of(
                "workflowId", workflowId,
                "workflowVersionId", workflowVersionId,
                "code", firstText(code, ""),
                "summary", safeResult)));
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
        entity.setReason(WorkflowTraceSanitizer.sanitizeAnswer(reason));
        entity.setMetadataJson(json(WorkflowTraceSanitizer.sanitizeGuardMetadata(metadata)));
        entity.setCreatedAt(LocalDateTime.now());
        safe(() -> guardLogMapper.insert(entity), "insert Supervisor policy decision");
    }

    public void finish(TraceHandle trace, boolean success, String code, String answer, Map<String, Object> metadata) {
        finish(trace, success, code, answer, metadata, null);
    }

    public void finish(TraceHandle trace, boolean success, String code, String answer,
                       Map<String, Object> metadata, WorkflowExecutionIdentity identity) {
        LocalDateTime ended = LocalDateTime.now();
        String resolved = status(success, code);
        boolean waiting = "WAITING_USER".equals(resolved) || "WAITING_APPROVAL".equals(resolved);
        String safeAnswer = WorkflowTraceSanitizer.sanitizeAnswer(answer);
        // Compute tokenCost from the original metadata before allowlist sanitization strips usage trees.
        int tokenCost = RuntimeRunLifecycleService.resolveTokenCost(metadata);
        Map<String, Object> safeMetadata = new LinkedHashMap<>(
                WorkflowTraceSanitizer.sanitizeFinishMetadata(metadata));
        safeMetadata.put("tokenCost", tokenCost);
        Integer latencyMs = waiting ? null : toInt(ChronoUnit.MILLIS.between(trace.startedAt(), ended));
        if (trace.rootId() != null) {
            // Direct conditional update — no selectById before finish.
            safe(() -> spanMapper.update(null, Wrappers.<RuntimeTraceSpanEntity>lambdaUpdate()
                    .eq(RuntimeTraceSpanEntity::getId, trace.rootId())
                    .set(RuntimeTraceSpanEntity::getStatus, resolved)
                    .set(RuntimeTraceSpanEntity::getOutputSummary, limit(safeAnswer, 4000))
                    .set(RuntimeTraceSpanEntity::getErrorCode, success || waiting ? null : code)
                    .set(RuntimeTraceSpanEntity::getErrorMessage, success || waiting ? null : limit(safeAnswer, 2000))
                    .set(RuntimeTraceSpanEntity::getMetadataJson, json(safeMetadata))
                    .set(RuntimeTraceSpanEntity::getLatencyMs, latencyMs)
                    .set(RuntimeTraceSpanEntity::getEndedAt, waiting ? null : ended)),
                    "finish Supervisor root span");
        }
        runLifecycleService.finishAgent(trace.traceId(), success, code, safeAnswer, safeMetadata, ended,
                identity, latencyMs, trace.startedAt());
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
        Object rawTraces = resultMetadata == null ? null : resultMetadata.get("workflowNodeTraces");
        if (!(rawTraces instanceof List<?> traces) || traces.isEmpty()) {
            return;
        }
        for (Object rawTrace : traces) {
            if (!(rawTrace instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> nodeTrace = WorkflowTraceSanitizer.sanitizeNodeTrace(raw);
            if (nodeTrace.isEmpty()) {
                continue;
            }
            String nodeId = firstText(nodeTrace.get("nodeId"));
            RuntimeTraceSpanEntity child = baseSpan(trace.traceId(), id(16), workflowSpan.getSpanId(),
                    "WORKFLOW_NODE", agent, config, input);
            child.setRuntimeType("LANGGRAPH4J");
            child.setNodeId(nodeId);
            String status = firstText(nodeTrace.get("status"), workflowSuccess ? "SUCCESS" : "FAILED");
            child.setStatus(status);
            Map<String, Object> childMetadata = new LinkedHashMap<>();
            childMetadata.put("workflowId", workflowId);
            childMetadata.put("workflowVersionId", workflowVersionId);
            childMetadata.put("workflowVersion", workflowVersion);
            putIfPresent(childMetadata, "nodeType", nodeTrace.get("nodeType"));
            putIfPresent(childMetadata, "attempt", nodeTrace.get("attempt"));
            putIfPresent(childMetadata, "maxAttempts", nodeTrace.get("maxAttempts"));
            putIfPresent(childMetadata, "errorPolicy", nodeTrace.get("errorPolicy"));
            putIfPresent(childMetadata, "failureCode", nodeTrace.get("failureCode"));
            putIfPresent(childMetadata, "fallbackNodeId", nodeTrace.get("fallbackNodeId"));
            putIfPresent(childMetadata, "outcomeClass", nodeTrace.get("outcomeClass"));
            putIfPresent(childMetadata, "businessOutcome", nodeTrace.get("businessOutcome"));
            putIfPresent(childMetadata, "traceSummary", nodeTrace.get("traceSummary"));
            putIfPresent(childMetadata, "interactionId", nodeTrace.get("interactionId"));
            putIfPresent(childMetadata, "interactionType", nodeTrace.get("interactionType"));
            child.setMetadataJson(json(childMetadata));
            child.setErrorCode(firstText(nodeTrace.get("failureCode"),
                    "FAILED".equalsIgnoreCase(status) ? workflowCode : null));
            child.setLatencyMs(toInt(longValue(nodeTrace.get("latencyMs"), 0L)));
            LocalDateTime started = epochMillisToLocalDateTime(nodeTrace.get("startedAt"), endedAt);
            child.setStartedAt(started);
            if ("WAITING_USER".equalsIgnoreCase(status)) {
                child.setEndedAt(null);
            } else {
                child.setEndedAt(epochMillisToLocalDateTime(nodeTrace.get("endedAt"), endedAt));
            }
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
        // This shared builder receives caller-controlled maps. Audit identity is only written
        // explicitly by persistence paths that receive WorkflowExecutionIdentity.
        entity.setExternalUserId(null);
        entity.setGlobalUserId(null);
        entity.setPageInstanceId(text(input.get("pageInstanceId")));
        return entity;
    }

    private String status(boolean success, String code) {
        if (success) return "SUCCESS";
        if ("SUPERVISOR_CONFIRMATION_REQUIRED".equalsIgnoreCase(code)) return "WAITING_APPROVAL";
        if ("RUNTIME_GRAPH_INTERACTION_WAITING".equalsIgnoreCase(code)) return "WAITING_USER";
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private LocalDateTime epochMillisToLocalDateTime(Object value, LocalDateTime fallback) {
        long millis = longValue(value, -1L);
        if (millis < 0L) {
            return fallback;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    public record TraceHandle(String traceId, String rootSpanId, Long rootId, LocalDateTime startedAt) {
    }
}
