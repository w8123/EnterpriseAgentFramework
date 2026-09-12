package com.enterprise.ai.runtime.mcp.application;

import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshot;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshotQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private final RuntimePublishedWorkflowSnapshotQuery workflowSnapshots;
    private final RuntimeTraceRootService traceRoots;
    private final RuntimeTraceEvidenceWriter traceEvidence;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executionExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    public RuntimeMcpToolExecutionService(
            RuntimeCapabilityCatalogClient capabilityClient,
            RuntimeGraphSpecExecutor graphSpecExecutor,
            RuntimePublishedWorkflowSnapshotQuery workflowSnapshots,
            RuntimeTraceRootService traceRoots,
            RuntimeTraceEvidenceWriter traceEvidence,
            RuntimeRunLifecycleService runLifecycleService,
            ObjectMapper objectMapper,
            @Qualifier("runtimeMcpExecutionExecutor") ExecutorService executionExecutor,
            @Qualifier("runtimeMcpTimeoutScheduler") ScheduledExecutorService timeoutScheduler) {
        this.capabilityClient = capabilityClient;
        this.graphSpecExecutor = graphSpecExecutor;
        this.workflowSnapshots = workflowSnapshots;
        this.traceRoots = traceRoots;
        this.traceEvidence = traceEvidence;
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
        RuntimeTraceRootService.Handle root = beginRootTrace(
                traceId, spanId, sourceKind, request, arguments, metadata, caller, startedAt);
        Long runId = runLifecycleService.beginMcp(
                traceId, spanId, sourceKind, request.sourceRef(), request.workflowVersionId(),
                request.toolName(), arguments, metadata, caller);
        if (runId == null) {
            finishRootTrace(root, false, "MCP_RUN_PERSISTENCE_FAILED", false);
            return ExecutionOutcome.failure("MCP_RUN_PERSISTENCE_FAILED", null, traceId);
        }

        ExecutionOutcome outcome;
        try {
            outcome = "CAPABILITY".equals(sourceKind)
                    ? executeCapability(request, arguments, caller, timeoutMs, traceId)
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
        finishRootTrace(root, outcome.success(), outcome.code(), !outcome.output().isEmpty());
        return outcome;
    }

    private ExecutionOutcome executeCapability(ExecutionRequest request,
                                               Map<String, Object> arguments,
                                               WorkflowExecutionIdentity identity,
                                               long timeoutMs,
                                               String traceId) {
        String qualifiedName = requiredText(request.sourceRef(), "sourceRef");
        if (request.capabilityContractHash() == null || !request.capabilityContractHash().matches("[0-9a-f]{64}")) {
            return ExecutionOutcome.failure("CAPABILITY_PUBLISHED_CONTRACT_REQUIRED", null, null);
        }
        Future<ExecutionOutcome> future = executionExecutor.submit(() -> {
            Map<String, Object> invocation = new LinkedHashMap<>();
            invocation.put("input", arguments);
            invocation.put("context", Map.of("supervisorTraceId", traceId));
            invocation.put("constraints", Map.of("expectedContractHash", request.capabilityContractHash()));
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
            return ExecutionOutcome.failure(result.code() == null ? "MCP_CAPABILITY_TECHNICAL_FAILED" : result.code(), null, null);
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
        RuntimePublishedWorkflowSnapshot snapshot;
        try {
            snapshot = workflowSnapshots.resolve(workflowId, versionId);
        } catch (RuntimePublishedWorkflowSnapshotQuery.LookupFailure failure) {
            String code = switch (failure.reason()) {
                case VERSION_NOT_FOUND -> "MCP_WORKFLOW_VERSION_NOT_FOUND";
                case SNAPSHOT_INVALID -> "MCP_WORKFLOW_SNAPSHOT_INVALID";
            };
            return ExecutionOutcome.failure(code, null, null);
        }
        if ((snapshot.projectId() == null && !StringUtils.hasText(snapshot.projectCode()))
                || !caller.authorizeProjectCredential(snapshot.projectId(), snapshot.projectCode())) {
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
                    snapshot.graphSpecJson(), snapshot.executionInput(arguments),
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

    private RuntimeTraceRootService.Handle beginRootTrace(String traceId,
                                                          String spanId,
                                                          String sourceKind,
                                                          ExecutionRequest request,
                                                          Map<String, Object> arguments,
                                                          Map<String, Object> metadata,
                                                          WorkflowExecutionIdentity identity,
                                                          LocalDateTime startedAt) {
        Map<String, Object> safeMetadata = new LinkedHashMap<>();
        safeMetadata.put("sourceKind", sourceKind);
        safeMetadata.put("sourceRef", request.sourceRef());
        safeMetadata.put("toolName", request.toolName());
        if (request.workflowVersionId() != null) safeMetadata.put("workflowVersionId", request.workflowVersionId());
        copyScalar(safeMetadata, metadata, "publicationId");
        copyScalar(safeMetadata, metadata, "revisionNo");
        copyScalar(safeMetadata, metadata, "environment");
        return traceRoots.startMcp(RuntimeTraceRootService.Start.builder()
                .traceId(traceId).spanId(spanId).spanType("MCP_TOOLS_CALL").runtimeType(sourceKind)
                .nodeId(request.sourceRef()).toolName(request.toolName())
                .projectCode(identity.projectCode()).tenantId(identity.tenantId())
                .input(arguments).metadataJson(json(safeMetadata)).startedAt(startedAt).build());
    }

    private void finishRootTrace(RuntimeTraceRootService.Handle root, boolean success, String code,
                                 boolean hasOutput) {
        String status = success ? "SUCCESS" : (code != null && code.contains("TIMED_OUT") ? "TIMED_OUT" : "ERROR");
        traceRoots.finishMcp(root, new RuntimeTraceRootService.Completion(
                status, code, hasOutput ? "[omitted]" : "", LocalDateTime.now(), null));
    }

    private void persistWorkflowNodeSpans(String traceId, String rootSpanId, String workflowId,
                                          WorkflowExecutionIdentity identity,
                                          RuntimeGraphSpecExecutionResult result) {
        Object raw = result.metadata() == null ? null : result.metadata().get("workflowNodeTraces");
        if (!(raw instanceof List<?> traces)) return;
        int count = 0;
        for (Object item : traces) {
            if (!(item instanceof Map<?, ?> trace) || count++ >= MAX_NODE_SPANS) continue;
            LocalDateTime startedAt = dateTime(trace.get("startedAt"), LocalDateTime.now());
            traceEvidence.appendChild(RuntimeTraceEvidenceWriter.ChildSpan.builder()
                    .traceId(traceId).spanId("span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                    .parentSpanId(rootSpanId).spanType("WORKFLOW_NODE").runtimeType(text(trace.get("nodeType")))
                    .agentId(workflowId).nodeId(text(trace.get("nodeId")))
                    .projectCode(identity.projectCode()).tenantId(identity.tenantId())
                    .status(traceStatus(text(trace.get("status")))).errorCode(text(trace.get("failureCode")))
                    .latencyMs(integer(trace.get("latencyMs"))).startedAt(startedAt)
                    .endedAt(dateTime(trace.get("endedAt"), startedAt)).createdAt(startedAt)
                    .metadataJson(json(WorkflowTraceSanitizer.sanitizeNodeTrace(trace))).build());
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

    private static String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    public record ExecutionRequest(String sourceKind, String sourceRef, Long workflowVersionId,
                                   String toolName, Map<String, Object> arguments,
                                   Map<String, Object> metadata, Long timeoutMs, String capabilityContractHash) {
        public ExecutionRequest(String sourceKind, String sourceRef, Long workflowVersionId, String toolName,
                                Map<String, Object> arguments, Map<String, Object> metadata, Long timeoutMs) {
            this(sourceKind, sourceRef, workflowVersionId, toolName, arguments, metadata, timeoutMs, null);
        }
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
