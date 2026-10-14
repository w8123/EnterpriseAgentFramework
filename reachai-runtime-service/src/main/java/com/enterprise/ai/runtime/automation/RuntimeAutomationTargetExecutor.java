package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionEventSink;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigQuery;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunSnapshots;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshot;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
final class RuntimeAutomationTargetExecutor {

    private final RuntimeAgentPublishedConfigQuery agentTargets;
    private final RuntimeAgentExecutionService agentExecutionService;
    private final RuntimeWorkflowExecutionQuery workflowTargets;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final RuntimeTraceRootService rootSpans;
    private final RuntimeTraceEvidenceWriter traceEvidence;
    private final RuntimeAutomationInteractionTerminationService interactionTerminationService;
    private final RuntimeAutomationJsonSupport json;
    private final ScheduledExecutorService runtimeAutomationTimeoutScheduler;

    ExecutionOutcome execute(RuntimeAutomationEntity automation,
                             RuntimeAutomationVersionEntity version,
                             RuntimeAutomationOccurrenceEntity occurrence,
                             String traceId,
                             Map<String, Object> input) {
        WorkflowExecutionIdentity identity = WorkflowExecutionIdentity.fromAutomation(
                automation.getTenantId(), automation.getProjectId(), automation.getProjectCode(),
                version.getPrincipalId());
        return "AGENT".equals(version.getTargetType())
                ? executeAgent(version, traceId, input, identity)
                : executeWorkflow(version, traceId, input, identity);
    }

    private ExecutionOutcome executeAgent(RuntimeAutomationVersionEntity version,
                                          String traceId,
                                          Map<String, Object> input,
                                          WorkflowExecutionIdentity identity) {
        RuntimeAgentPublishedConfigQuery.Target target;
        try {
            target = agentTargets.resolve(version.getTargetId(), version.getTargetVersionId());
        } catch (RuntimeAgentPublishedConfigQuery.LookupFailure unavailable) {
            return ExecutionOutcome.failure(traceId, "AUTOMATION_TARGET_VERSION_UNAVAILABLE",
                    "Pinned Agent version is unavailable", false);
        }
        RuntimeAgentExecutionCancellation cancellation = new RuntimeAgentExecutionCancellation();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeout = scheduleTimeout(version, () -> {
            timedOut.set(true);
            cancellation.cancel();
        });
        try {
            Map<String, Object> response = agentExecutionService.executePublishedConfig(
                    target.agentId(), target.versionId(), input, true,
                    RuntimeAgentExecutionEventSink.NOOP, cancellation, identity);
            Map<String, Object> metadata = map(response.get("metadata"));
            String code = firstText(text(response.get("code")), text(metadata.get("code")));
            String answer = text(response.get("answer"));
            boolean interaction = response.get("uiRequest") != null
                    || containsWaiting(code)
                    || containsWaiting(text(response.get("status")))
                    || containsWaiting(text(metadata.get("status")));
            if (interaction) {
                interactionTerminationService.terminate(
                        firstText(text(metadata.get("interactionId")), interactionId(response.get("uiRequest"))),
                        traceId, identity);
                Map<String, Object> terminalMetadata = new LinkedHashMap<>(metadata);
                terminalMetadata.put("interactionPending", false);
                terminalMetadata.put("automationFailClosed", true);
                runLifecycleService.finishAgent(traceId, false,
                        "AUTOMATION_INTERACTION_REQUIRED",
                        "Scheduled Agent execution requires human interaction and was stopped fail-closed",
                        terminalMetadata, null, identity);
                return new ExecutionOutcome(false, true, traceId,
                        "AUTOMATION_INTERACTION_REQUIRED",
                        "Scheduled Agent execution requires human interaction and was stopped fail-closed",
                        terminalMetadata, false);
            }
            boolean success = Boolean.TRUE.equals(response.get("success"));
            if (success) return new ExecutionOutcome(true, false, traceId, code, answer, metadata, false);
            return new ExecutionOutcome(false, false, traceId,
                    firstText(code, "AUTOMATION_AGENT_EXECUTION_FAILED"),
                    firstText(answer, "Agent execution failed"), metadata, retryable(code));
        } catch (RuntimeAgentExecutionCancellation.CancellationSignal cancelled) {
            return ExecutionOutcome.failure(traceId,
                    timedOut.get() ? "AUTOMATION_EXECUTION_TIMED_OUT" : "AUTOMATION_EXECUTION_CANCELLED",
                    timedOut.get() ? "Automation execution timed out" : "Automation execution was cancelled",
                    !timedOut.get());
        } catch (Exception failure) {
            return ExecutionOutcome.failure(traceId, "AUTOMATION_AGENT_EXECUTION_FAILED", safe(failure), true);
        } finally {
            timeout.cancel(false);
        }
    }

    private ExecutionOutcome executeWorkflow(RuntimeAutomationVersionEntity version,
                                             String traceId,
                                             Map<String, Object> input,
                                             WorkflowExecutionIdentity identity) {
        RuntimeWorkflowExecutionQuery.Target target;
        try {
            target = workflowTargets.resolveOne(version.getTargetId(), version.getTargetVersionId());
        } catch (RuntimeWorkflowExecutionQuery.LookupFailure unavailable) {
            return ExecutionOutcome.failure(traceId, "AUTOMATION_TARGET_VERSION_UNAVAILABLE",
                    "Pinned Workflow version is unavailable", false);
        }
        RuntimeWorkflowExecutionView workflow = target.workflow();
        RuntimeWorkflowPublishedVersionView published = target.version();
        RuntimePublishedWorkflowSnapshot snapshot;
        try {
            snapshot = RuntimePublishedWorkflowSnapshot.read(published);
        } catch (IllegalArgumentException invalid) {
            return ExecutionOutcome.failure(traceId, "AUTOMATION_WORKFLOW_SNAPSHOT_INVALID",
                    invalid.getMessage(), false);
        }
        RuntimeGraphSpecExecutionCancellation cancellation = RuntimeGraphSpecExecutionCancellation.none();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeout = scheduleTimeout(version, () -> {
            timedOut.set(true);
            cancellation.cancel();
        });
        WorkflowTrace trace = beginWorkflowTrace(traceId, workflow, published, input, identity);
        try {
            RuntimeGraphSpecExecutionResult result = graphSpecExecutor.execute(
                    snapshot.graphSpecJson(), snapshot.executionInput(input),
                    RuntimeGraphSpecExecutionEventSink.NOOP, cancellation, identity);
            if (timedOut.get()) {
                finishWorkflowTrace(trace, new RuntimeGraphSpecExecutionResult(
                        false, "AUTOMATION_EXECUTION_TIMED_OUT", "Automation execution timed out",
                        result.nodeId(), result.nodeType(), result.steps(), result.metadata()));
                return ExecutionOutcome.failure(traceId, "AUTOMATION_EXECUTION_TIMED_OUT",
                        "Automation execution timed out", false);
            }
            if (result.isWaitingUser()) {
                interactionTerminationService.terminate(
                        firstText(result.interactionId(), interactionId(result.uiRequest())), traceId, identity);
                Map<String, Object> terminalMetadata = new LinkedHashMap<>(result.metadata());
                terminalMetadata.put("interactionPending", false);
                terminalMetadata.put("automationFailClosed", true);
                RuntimeGraphSpecExecutionResult terminal = new RuntimeGraphSpecExecutionResult(
                        false, "AUTOMATION_INTERACTION_REQUIRED",
                        "Scheduled Workflow execution requires human interaction and was stopped fail-closed",
                        result.nodeId(), result.nodeType(), result.steps(), terminalMetadata);
                finishWorkflowTrace(trace, terminal);
                return new ExecutionOutcome(false, true, traceId,
                        "AUTOMATION_INTERACTION_REQUIRED",
                        "Scheduled Workflow execution requires human interaction and was stopped fail-closed",
                        terminalMetadata, false);
            }
            finishWorkflowTrace(trace, result);
            if (result.success()) {
                return new ExecutionOutcome(true, false, traceId, result.code(), result.answer(),
                        result.metadata(), false);
            }
            return new ExecutionOutcome(false, false, traceId,
                    firstText(result.code(), "AUTOMATION_WORKFLOW_EXECUTION_FAILED"),
                    firstText(result.answer(), "Workflow execution failed"), result.metadata(),
                    retryable(result.code()));
        } catch (Exception failure) {
            RuntimeGraphSpecExecutionResult result = new RuntimeGraphSpecExecutionResult(
                    false,
                    timedOut.get() ? "AUTOMATION_EXECUTION_TIMED_OUT" : "AUTOMATION_WORKFLOW_EXECUTION_FAILED",
                    safe(failure), null, null, List.of(), Map.of());
            finishWorkflowTrace(trace, result);
            return ExecutionOutcome.failure(traceId, result.code(), result.answer(), !timedOut.get());
        } finally {
            timeout.cancel(false);
        }
    }

    private WorkflowTrace beginWorkflowTrace(String traceId,
                                             RuntimeWorkflowExecutionView workflow,
                                             RuntimeWorkflowPublishedVersionView version,
                                             Map<String, Object> input,
                                             WorkflowExecutionIdentity identity) {
        String executionEngine = WorkflowSemanticValues.normalizeExecutionEngine(
                StringUtils.hasText(workflow.getExecutionEngine())
                        ? workflow.getExecutionEngine() : WorkflowSemanticValues.ENGINE_GRAPH_SPEC);
        String spanId = "span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
        var root = rootSpans.start(RuntimeTraceRootService.Start.builder()
                .traceId(traceId).spanId(spanId).spanType("WORKFLOW").runtimeType(executionEngine)
                .agentId(workflow.getId()).agentName(workflow.getName()).nodeId(workflow.getId())
                .projectCode(workflow.getProjectCode()).tenantId(identity.tenantId()).input(input)
                .metadataJson(json.write(Map.of(
                "sourceType", "AUTOMATION",
                "workflowId", workflow.getId(),
                "workflowVersionId", version.getId(),
                "workflowVersion", version.getVersion())))
                .startedAt(now).build());
        runLifecycleService.beginPublishedWorkflow(
                traceId, spanId, "AUTOMATION", new RuntimeRunSnapshots.PublishedWorkflow(
                        workflow.getId(), workflow.getKeySlug(), workflow.getName(), workflow.getProjectId(),
                        workflow.getProjectCode(), executionEngine, version.getId(), version.getVersion(),
                        version.getGraphSpecSnapshotJson()), input, identity);
        return new WorkflowTrace(root.id(), traceId, spanId, now, workflow.getId(), workflow.getName(),
                workflow.getProjectId(), workflow.getProjectCode(), identity.tenantId(), version.getId(),
                version.getVersion(), executionEngine);
    }

    private void finishWorkflowTrace(WorkflowTrace trace, RuntimeGraphSpecExecutionResult result) {
        LocalDateTime ended = LocalDateTime.now(java.time.Clock.systemUTC());
        boolean waiting = result.isWaitingUser();
        String status = waiting ? "WAITING_USER" : (result.success() ? "SUCCESS" : "ERROR");
        if (!rootSpans.finish(new RuntimeTraceRootService.Handle(
                trace.rootId(), trace.traceId(), trace.rootSpanId(), trace.startedAt()),
                new RuntimeTraceRootService.Completion(status, result.code(), result.answer(), ended,
                        json.write(Map.of("workflowResult", WorkflowTraceSanitizer.sanitizeWorkflowResult(result.metadata())))))) return;
        recordWorkflowNodeEvidence(trace, result, ended);
        runLifecycleService.finishWorkflow(trace.traceId(), result.success(), result.code(), result.answer(),
                result.steps() == null ? 0 : result.steps().size(), result.metadata());
    }

    private void recordWorkflowNodeEvidence(WorkflowTrace trace, RuntimeGraphSpecExecutionResult result,
                                            LocalDateTime ended) {
        // The executor already projects canonical events even when the live delegate is NOOP.
        // Persist those actual execution facts; never recover them from the saved canvas or input.
        Object projected = result.metadata() == null ? null : result.metadata().get("workflowNodeTraces");
        if (!(projected instanceof List<?> nodes)) return;
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> node = WorkflowTraceSanitizer.sanitizeNodeTrace(raw);
            String nodeId = text(node.get("nodeId"));
            if (!StringUtils.hasText(nodeId)) continue;
            String status = firstText(text(node.get("status")), result.success() ? "SUCCESS" : "FAILED");
            String toolName = text(node.get("qualifiedName"));
            String failureCode = text(node.get("failureCode"));
            Map<String, Object> metadata = new LinkedHashMap<>(node);
            metadata.put("sourceType", "AUTOMATION");
            metadata.put("workflowId", trace.workflowId());
            metadata.put("workflowVersionId", trace.workflowVersionId());
            metadata.put("workflowVersion", trace.workflowVersion());
            int latency = node.get("latencyMs") instanceof Number n
                    ? (int) Math.min(Integer.MAX_VALUE, Math.max(0, n.longValue())) : 0;
            try {
                traceEvidence.appendChild(RuntimeTraceEvidenceWriter.ChildSpan.builder()
                        .traceId(trace.traceId()).spanId("span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20))
                        .parentSpanId(trace.rootSpanId()).spanType("WORKFLOW_NODE").runtimeType(trace.runtimeType())
                        .agentId(trace.workflowId()).agentName(trace.workflowName()).nodeId(nodeId).toolName(toolName)
                        .projectCode(trace.projectCode()).tenantId(trace.tenantId()).status(status)
                        .metadataJson(json.write(metadata)).errorCode(failureCode).latencyMs(latency)
                        .startedAt(nodeTime(node.get("startedAt"), ended))
                        .endedAt("WAITING_USER".equals(status) ? null : nodeTime(node.get("endedAt"), ended))
                        .createdAt(ended).build());
                if ("TOOL".equals(node.get("nodeType")) && StringUtils.hasText(toolName)) {
                    traceEvidence.appendToolCall(RuntimeTraceEvidenceWriter.ToolCall.builder()
                            .traceId(trace.traceId()).agentName(trace.workflowName()).intentType("AUTOMATION_WORKFLOW_NODE")
                            .projectId(trace.projectId()).projectCode(trace.projectCode()).tenantId(trace.tenantId())
                            .origin("AUTOMATION").toolName(toolName).argsJson("{}")
                            .resultSummary(WorkflowTraceSanitizer.sanitizeAnswer(result.answer()))
                            .success("SUCCESS".equals(status) || "BUSINESS_TERMINAL".equals(status))
                            .errorCode(failureCode).elapsedMs(latency).retrievalTraceJson(json.write(metadata))
                            .createTime(ended).build());
                }
            } catch (RuntimeException failure) {
                // A Trace storage outage must not retry an already completed business operation.
                log.warn("Cannot persist Automation node evidence: {}", failure.getClass().getSimpleName());
            }
        }
    }

    private LocalDateTime nodeTime(Object value, LocalDateTime fallback) {
        return value instanceof Number n ? LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneOffset.UTC) : fallback;
    }

    private ScheduledFuture<?> scheduleTimeout(RuntimeAutomationVersionEntity version, Runnable cancellation) {
        int seconds = version.getTimeoutSeconds() == null ? 900 : version.getTimeoutSeconds();
        return runtimeAutomationTimeoutScheduler.schedule(cancellation,
                Math.max(10, Math.min(86_400, seconds)), TimeUnit.SECONDS);
    }

    private boolean retryable(String code) {
        String value = RuntimeAutomationTypes.upper(code);
        if (value == null) return true;
        return !(value.contains("INVALID") || value.contains("FORBIDDEN")
                || value.contains("NOT_FOUND") || value.contains("VERSION_UNAVAILABLE")
                || value.contains("INTERACTION_REQUIRED") || value.contains("GUARD_DENIED"));
    }

    private boolean containsWaiting(String value) {
        if (!StringUtils.hasText(value)) return false;
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("WAITING_USER") || normalized.contains("INTERACTION_WAITING")
                || normalized.contains("APPROVAL_REQUIRED");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String interactionId(Object uiRequest) {
        return uiRequest instanceof Map<?, ?> raw ? text(raw.get("interactionId")) : null;
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private String safe(Throwable error) {
        String value = firstText(error.getMessage(), error.getClass().getSimpleName(), "Automation execution failed");
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    record ExecutionOutcome(
            boolean success,
            boolean interactionRequired,
            String traceId,
            String code,
            String message,
            Map<String, Object> metadata,
            boolean retryable) {

        static ExecutionOutcome failure(String traceId, String code, String message, boolean retryable) {
            return new ExecutionOutcome(false, false, traceId, code, message, Map.of(), retryable);
        }
    }

    private record WorkflowTrace(Long rootId, String traceId, String rootSpanId, LocalDateTime startedAt,
                                 String workflowId, String workflowName, Long projectId, String projectCode,
                                 String tenantId, Long workflowVersionId, String workflowVersion, String runtimeType) {
    }
}
