package com.enterprise.ai.runtime.trace;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/** Trace owns append-only child evidence and tool audit rows; callers supply sanitized immutable facts. */
@Service
@RequiredArgsConstructor
public class RuntimeTraceEvidenceWriter {
    private static final Set<String> CHILD_TYPES = Set.of("PLAN", "REPLAN", "SKILL_CONTEXT", "WORKFLOW_TOOL",
            "WORKFLOW_NODE", "A2A_DELEGATION", "MANAGED_EXECUTOR");
    private final RuntimeTraceSpanMapper spans;
    private final RuntimeToolCallLogMapper tools;

    public void appendChild(ChildSpan request) {
        Objects.requireNonNull(request, "request");
        require(request.traceId(), "traceId");
        require(request.spanId(), "spanId");
        require(request.parentSpanId(), "parentSpanId");
        if (request.spanId().equals(request.parentSpanId()) || !CHILD_TYPES.contains(request.spanType())) {
            throw new IllegalArgumentException("Invalid child span identity or type");
        }
        var row = new RuntimeTraceSpanEntity();
        row.setTraceId(request.traceId());
        row.setSpanId(request.spanId());
        row.setParentSpanId(request.parentSpanId());
        row.setSpanType(request.spanType());
        row.setRuntimeType(request.runtimeType());
        row.setAgentId(request.agentId());
        row.setAgentName(request.agentName());
        row.setNodeId(request.nodeId());
        row.setToolName(request.toolName());
        row.setModelInstanceId(request.modelInstanceId());
        row.setProjectCode(request.projectCode());
        row.setTenantId(request.tenantId());
        row.setAppId(request.appId());
        row.setPageInstanceId(request.pageInstanceId());
        row.setStatus(request.status());
        row.setInputSummary(request.inputSummary());
        row.setOutputSummary(request.outputSummary());
        row.setMetadataJson(request.metadataJson());
        row.setErrorCode(request.errorCode());
        row.setErrorMessage(request.errorMessage());
        row.setLatencyMs(request.latencyMs());
        row.setTokenCost(request.tokenCost());
        row.setStartedAt(request.startedAt());
        row.setEndedAt(request.endedAt());
        row.setCreatedAt(request.createdAt());
        spans.insert(row);
    }

    public void appendToolCall(ToolCall request) {
        Objects.requireNonNull(request, "request");
        require(request.traceId(), "traceId");
        require(request.toolName(), "toolName");
        var row = new RuntimeToolCallLogEntity();
        row.setTraceId(request.traceId());
        row.setSessionId(request.sessionId());
        row.setUserId(request.userId());
        row.setAgentName(request.agentName());
        row.setIntentType(request.intentType());
        row.setProjectId(request.projectId());
        row.setProjectCode(request.projectCode());
        row.setEnvironment(request.environment());
        row.setTenantId(request.tenantId());
        row.setAppId(request.appId());
        row.setExternalUserId(request.externalUserId());
        row.setGlobalUserId(request.globalUserId());
        row.setPageInstanceId(request.pageInstanceId());
        row.setOrigin(request.origin());
        row.setToolName(request.toolName());
        row.setArgsJson(request.argsJson());
        row.setResultSummary(request.resultSummary());
        row.setSuccess(request.success());
        row.setErrorCode(request.errorCode());
        row.setElapsedMs(request.elapsedMs());
        row.setTokenCost(request.tokenCost());
        row.setRetrievalTraceJson(request.retrievalTraceJson());
        row.setCreateTime(request.createTime());
        tools.insert(row);
    }

    private void require(String value, String name) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(name + " is required");
    }

    @Builder
    public record ChildSpan(String traceId,
            String spanId,
            String parentSpanId,
            String spanType,
            String runtimeType,
            String agentId,
            String agentName,
            String nodeId,
            String toolName,
            String modelInstanceId,
            String projectCode,
            String tenantId,
            String appId,
            String pageInstanceId,
            String status,
            String inputSummary,
            String outputSummary,
            String metadataJson,
            String errorCode,
            String errorMessage,
            Integer latencyMs,
            Integer tokenCost,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            LocalDateTime createdAt) { }

    @Builder
    public record ToolCall(String traceId,
            String sessionId,
            String userId,
            String agentName,
            String intentType,
            Long projectId,
            String projectCode,
            String environment,
            String tenantId,
            String appId,
            String externalUserId,
            String globalUserId,
            String pageInstanceId,
            String origin,
            String toolName,
            String argsJson,
            String resultSummary,
            Boolean success,
            String errorCode,
            Integer elapsedMs,
            Integer tokenCost,
            String retrievalTraceJson,
            LocalDateTime createTime) { }
}
