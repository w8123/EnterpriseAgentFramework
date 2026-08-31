package com.enterprise.ai.runtime.mcp.application;

import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Governed Runtime boundary for version-pinned MCP Capability and Workflow execution. */
@Service
public class RuntimeMcpToolExecutionService {

    private static final long MAX_TIMEOUT_MS = 115_000L;
    private static final int MAX_NODE_SPANS = 500;

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final RuntimeWorkflowVersionMapper versionMapper;
    private final RuntimeWorkflowDefinitionService workflowService;
    private final RuntimeTraceSpanMapper traceSpanMapper;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executionExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    public RuntimeMcpToolExecutionService(
            RuntimeCapabilityCatalogClient capabilityClient,
            RuntimeGraphSpecExecutor graphSpecExecutor,
            RuntimeWorkflowVersionMapper versionMapper,
            RuntimeWorkflowDefinitionService workflowService,
            RuntimeTraceSpanMapper traceSpanMapper,
            RuntimeRunLifecycleService runLifecycleService,
            ObjectMapper objectMapper,
            @Qualifier("runtimeMcpExecutionExecutor") ExecutorService executionExecutor,
            @Qualifier("runtimeMcpTimeoutScheduler") ScheduledExecutorService timeoutScheduler) {
        this.capabilityClient = capabilityClient;
        this.graphSpecExecutor = graphSpecExecutor;
        this.versionMapper = versionMapper;
        this.workflowService = workflowService;
        this.traceSpanMapper = traceSpanMapper;
        this.runLifecycleService = runLifecycleService;
        this.objectMapper = objectMapper;
        this.executionExecutor = executionExecutor;
        this.timeoutScheduler = timeoutScheduler;
    }

    public ExecutionOutcome execute(ExecutionRequest request, WorkflowExecutionIdentity caller) {
        requireRequest(request, caller);
        String sourceKind = request.sourceKind().trim().toUpperCase(Locale.ROOT);
        if (!List.of("CAPABILITY", "WORKFLOW").contains(sourceKind)) {
            return ExecutionOutcome.failure("MCP_SOURCE_KIND_INVALID", null, null);
        }
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        Map<String, Object> metadata = request.metadata() == null ? Map.of() : request.metadata();
        long timeoutMs = Math.max(1_000L, Math.min(MAX_TIMEOUT_MS,
                request.timeoutMs() == null ? MAX_TIMEOUT_MS : request.timeoutMs()));
        String traceId = "mcp_" + UUID.randomUUID().toString().replace("-", "");
        String spanId = "span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        LocalDateTime startedAt = LocalDateTime.now();
        RuntimeTraceSpanEntity root = beginRootTrace(
                traceId, spanId, sourceKind, request, arguments, metadata, caller, startedAt);
        Long runId = runLifecycleService.beginMcp(
                traceId, spanId, sourceKind, request.sourceRef(), request.workflowVersionId(),
                request.toolName(), arguments, metadata, caller);
        if (runId == null) {
            finishRootTrace(root, false, "MCP_RUN_PERSISTENCE_FAILED", false, startedAt);
            return ExecutionOutcome.failure("MCP_RUN_PERSISTENCE_FAILED", null, traceId);
        }

        ExecutionOutcome outcome;
        try {
            outcome = "CAPABILITY".equals(sourceKind)
                    ? executeCapability(request, arguments, caller, timeoutMs)
                    : executeWorkflow(request, arguments, caller, timeoutMs, traceId, spanId);
        } catch (RejectedExecutionException overloaded) {
            outcome = ExecutionOutcome.failure("MCP_RUNTIME_OVERLOADED", String.valueOf(runId), traceId);
        } catch (RuntimeException unexpected) {
            outcome = ExecutionOutcome.failure(
                    "WORKFLOW".equals(sourceKind)
                            ? "MCP_WORKFLOW_EXECUTION_FAILED" : "MCP_CAPABILITY_TECHNICAL_FAILED",
                    String.valueOf(runId), traceId);
        }
        outcome = outcome.withIds(String.valueOf(runId), traceId);
        Map<String, Object> finishMetadata = new LinkedHashMap<>(metadata);
        finishMetadata.put("sourceKind", sourceKind);
        finishMetadata.put("toolName", request.toolName());
        if (outcome.nodeCount() != null) finishMetadata.put("nodeCount", outcome.nodeCount());
        runLifecycleService.finishMcp(traceId, outcome.success(), outcome.code(),
                !outcome.output().isEmpty(), finishMetadata);
        finishRootTrace(root, outcome.success(), outcome.code(), !outcome.output().isEmpty(), startedAt);
        return outcome;
    }

    private ExecutionOutcome executeCapability(ExecutionRequest request,
                                               Map<String, Object> arguments,
                                               WorkflowExecutionIdentity identity,
                                               long timeoutMs) {
        String qualifiedName = requiredText(request.sourceRef(), "sourceRef");
        Future<ExecutionOutcome> future = executionExecutor.submit(() -> {
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("input", arguments);
            invocation.put("context", Map.of());
            invocation.put(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE, identity);
            var result = capabilityClient.invokeTool(qualifiedName, invocation);
            if (result == null) return ExecutionOutcome.failure("MCP_CAPABILITY_RESPONSE_MISSING", null, null);
            if (result.status() == CapabilityInvocationStatus.SUCCEEDED) {
                Map<String, Object> output = new LinkedHashMap<>();
                if (result.data() != null) output.put("data", result.data());
                return ExecutionOutcome.success(output, null, null, null);
            }
            if (result.status() == CapabilityInvocationStatus.BUSINESS_FAILED) {
                return ExecutionOutcome.failure("MCP_CAPABILITY_BUSINESS_FAILED", null, null);
            }
            return ExecutionOutcome.failure("MCP_CAPABILITY_TECHNICAL_FAILED", null, null);
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return ExecutionOutcome.failure("MCP_CAPABILITY_EXECUTION_TIMED_OUT", null, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ExecutionOutcome.failure("MCP_CAPABILITY_EXECUTION_INTERRUPTED", null, null);
        } catch (Exception failed) {
            return ExecutionOutcome.failure("MCP_CAPABILITY_TECHNICAL_FAILED", null, null);
        }
    }

    private ExecutionOutcome executeWorkflow(ExecutionRequest request,
                                             Map<String, Object> arguments,
                                             WorkflowExecutionIdentity caller,
                                             long timeoutMs,
                                             String traceId,
                                             String rootSpanId) {
        String workflowId = requiredText(request.sourceRef(), "sourceRef");
        Long versionId = request.workflowVersionId();
        if (versionId == null || versionId <= 0) {
            return ExecutionOutcome.failure("MCP_WORKFLOW_VERSION_REQUIRED", null, null);
        }
        RuntimeWorkflowVersionEntity version = versionMapper.selectById(versionId);
        if (version == null || !workflowId.equals(version.getWorkflowId())) {
            return ExecutionOutcome.failure("MCP_WORKFLOW_VERSION_NOT_FOUND", null, null);
        }
        if (!StringUtils.hasText(version.getGraphSpecSnapshotJson())) {
            return ExecutionOutcome.failure("MCP_WORKFLOW_SNAPSHOT_INVALID", null, null);
        }
        RuntimeWorkflowDefinitionEntity workflow = workflowService.findById(workflowId).orElse(null);
        if (!workflowScopeMatches(version, workflow, caller)) {
            return ExecutionOutcome.failure("MCP_WORKFLOW_PROJECT_SCOPE_DENIED", null, null);
        }
        RuntimeGraphSpecExecutionCancellation cancellation = RuntimeGraphSpecExecutionCancellation.none();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeout = timeoutScheduler.schedule(() -> {
            timedOut.set(true);
            cancellation.cancel();
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            RuntimeGraphSpecExecutionResult result = graphSpecExecutor.execute(
                    version.getGraphSpecSnapshotJson(), arguments,
                    RuntimeGraphSpecExecutionEventSink.NOOP, cancellation, caller);
            persistWorkflowNodeSpans(traceId, rootSpanId, workflowId, caller, result);
            int nodeCount = result.steps() == null ? 0 : result.steps().size();
            if (timedOut.get() || "RUNTIME_GRAPH_CANCELLED".equals(result.code())) {
                return ExecutionOutcome.failure("MCP_WORKFLOW_EXECUTION_TIMED_OUT", null, null, nodeCount);
            }
            if (result.isWaitingUser()) {
                return ExecutionOutcome.failure("MCP_WORKFLOW_INTERACTION_UNSUPPORTED", null, null, nodeCount);
            }
            if (!result.success()) {
                return ExecutionOutcome.failure(firstText(result.code(), "MCP_WORKFLOW_EXECUTION_FAILED"),
                        null, null, nodeCount);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            if (result.answer() != null) output.put("answer", result.answer());
            return ExecutionOutcome.success(output, null, null, nodeCount);
        } finally {
            timeout.cancel(false);
        }
    }

    private boolean workflowScopeMatches(RuntimeWorkflowVersionEntity version,
                                         RuntimeWorkflowDefinitionEntity current,
                                         WorkflowExecutionIdentity caller) {
        Long projectId = null;
        String projectCode = null;
        if (StringUtils.hasText(version.getSnapshotJson())) {
            try {
                JsonNode snapshot = objectMapper.readTree(version.getSnapshotJson());
                projectId = longValue(snapshot == null ? null : snapshot.get("projectId"));
                projectCode = jsonText(snapshot == null ? null : snapshot.get("projectCode"));
            } catch (Exception ignored) {
                return false;
            }
        }
        if (projectId == null && !StringUtils.hasText(projectCode) && current != null) {
            projectId = current.getProjectId();
            projectCode = current.getProjectCode();
        }
        return (projectId != null || StringUtils.hasText(projectCode))
                && caller.authorizeProjectCredential(projectId, projectCode);
    }

    private RuntimeTraceSpanEntity beginRootTrace(String traceId,
                                                  String spanId,
                                                  String sourceKind,
                                                  ExecutionRequest request,
                                                  Map<String, Object> arguments,
                                                  Map<String, Object> metadata,
                                                  WorkflowExecutionIdentity identity,
                                                  LocalDateTime startedAt) {
        RuntimeTraceSpanEntity root = new RuntimeTraceSpanEntity();
        root.setTraceId(traceId);
        root.setSpanId(spanId);
        root.setSpanType("MCP_TOOLS_CALL");
        root.setRuntimeType(sourceKind);
        root.setNodeId(request.sourceRef());
        root.setToolName(request.toolName());
        root.setProjectCode(identity.projectCode());
        root.setTenantId(identity.tenantId());
        root.setStatus("RUNNING");
        root.setInputSummary(json(WorkflowTraceSanitizer.sanitizeInputSummary(arguments)));
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        safeMetadata.put("sourceKind", sourceKind);
        safeMetadata.put("sourceRef", request.sourceRef());
        safeMetadata.put("toolName", request.toolName());
        if (request.workflowVersionId() != null) safeMetadata.put("workflowVersionId", request.workflowVersionId());
        copyScalar(safeMetadata, metadata, "publicationId");
        copyScalar(safeMetadata, metadata, "revisionNo");
        copyScalar(safeMetadata, metadata, "environment");
        root.setMetadataJson(json(safeMetadata));
        root.setStartedAt(startedAt);
        root.setCreatedAt(startedAt);
        traceSpanMapper.insert(root);
        return root;
    }

    private void finishRootTrace(RuntimeTraceSpanEntity root, boolean success, String code,
                                 boolean hasOutput, LocalDateTime startedAt) {
        if (root == null || root.getId() == null) return;
        LocalDateTime ended = LocalDateTime.now();
        root.setStatus(success ? "SUCCESS" : (code != null && code.contains("TIMED_OUT") ? "TIMED_OUT" : "ERROR"));
        root.setOutputSummary(hasOutput ? "[omitted]" : "");
        root.setErrorCode(success ? null : code);
        root.setErrorMessage(success ? null : WorkflowTraceSanitizer.sanitizeRejectionSummary(code));
        root.setLatencyMs((int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, ChronoUnit.MILLIS.between(startedAt, ended))));
        root.setEndedAt(ended);
        traceSpanMapper.updateById(root);
    }

    private void persistWorkflowNodeSpans(String traceId, String rootSpanId, String workflowId,
                                          WorkflowExecutionIdentity identity,
                                          RuntimeGraphSpecExecutionResult result) {
        Object raw = result.metadata() == null ? null : result.metadata().get("workflowNodeTraces");
        if (!(raw instanceof List<?> traces)) return;
        int count = 0;
        for (Object item : traces) {
            if (!(item instanceof Map<?, ?> trace) || count++ >= MAX_NODE_SPANS) continue;
            RuntimeTraceSpanEntity span = new RuntimeTraceSpanEntity();
            span.setTraceId(traceId);
            span.setSpanId("span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            span.setParentSpanId(rootSpanId);
            span.setSpanType("WORKFLOW_NODE");
            span.setRuntimeType(text(trace.get("nodeType")));
            span.setAgentId(workflowId);
            span.setNodeId(text(trace.get("nodeId")));
            span.setProjectCode(identity.projectCode());
            span.setTenantId(identity.tenantId());
            span.setStatus(traceStatus(text(trace.get("status"))));
            span.setErrorCode(text(trace.get("failureCode")));
            span.setLatencyMs(integer(trace.get("latencyMs")));
            span.setStartedAt(dateTime(trace.get("startedAt"), LocalDateTime.now()));
            span.setEndedAt(dateTime(trace.get("endedAt"), span.getStartedAt()));
            span.setMetadataJson(json(WorkflowTraceSanitizer.sanitizeNodeTrace(trace)));
            span.setCreatedAt(span.getStartedAt());
            traceSpanMapper.insert(span);
        }
    }

    private void requireRequest(ExecutionRequest request, WorkflowExecutionIdentity caller) {
        if (request == null || caller == null || caller.source() != WorkflowExecutionIdentity.Source.MCP_REMOTE_CLIENT
                || !StringUtils.hasText(request.sourceKind())
                || !StringUtils.hasText(request.sourceRef())
                || !StringUtils.hasText(request.toolName())
                || !StringUtils.hasText(caller.tenantId())
                || !caller.canResolveProjectCredential()) {
            throw new IllegalArgumentException("MCP execution request or trusted scope is invalid");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private void copyScalar(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source == null) return;
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) target.put(key, value);
    }

    private static String traceStatus(String value) {
        if (value == null) return "ERROR";
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "COMPLETED", "SUCCESS" -> "SUCCESS";
            case "WAITING_USER", "SUSPENDED" -> "WAITING_USER";
            case "CANCELLED" -> "CANCELLED";
            default -> "ERROR";
        };
    }

    private static LocalDateTime dateTime(Object value, LocalDateTime fallback) {
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(number.longValue()), ZoneId.systemDefault());
        }
        return fallback;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try {
            return value == null ? null : Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Long longValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isIntegralNumber()) return value.longValue();
        try {
            return Long.parseLong(value.asText().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String jsonText(JsonNode value) {
        return value == null || value.isNull() || !value.isValueNode() ? null : text(value.asText());
    }

    private static String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    public record ExecutionRequest(String sourceKind, String sourceRef, Long workflowVersionId,
                                   String toolName, Map<String, Object> arguments,
                                   Map<String, Object> metadata, Long timeoutMs) {
    }

    public record ExecutionOutcome(boolean success, String code, Map<String, Object> output,
                                   String runId, String traceId, Integer nodeCount) {
        public ExecutionOutcome {
            output = output == null ? Map.of() : Map.copyOf(output);
        }

        static ExecutionOutcome success(Map<String, Object> output, String runId,
                                        String traceId, Integer nodeCount) {
            return new ExecutionOutcome(true, "OK", output, runId, traceId, nodeCount);
        }

        static ExecutionOutcome failure(String code, String runId, String traceId) {
            return failure(code, runId, traceId, null);
        }

        static ExecutionOutcome failure(String code, String runId, String traceId, Integer nodeCount) {
            return new ExecutionOutcome(false, code, Map.of(), runId, traceId, nodeCount);
        }

        ExecutionOutcome withIds(String runId, String traceId) {
            return new ExecutionOutcome(success, code, output, runId, traceId, nodeCount);
        }
    }
}
