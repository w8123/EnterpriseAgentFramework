package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionMapper;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.trace.WorkflowTraceSanitizer;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.execution.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reachai.runtime.automation.enabled", havingValue = "true")
final class RuntimeAutomationTargetExecutor {

    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigVersionMapper agentVersionMapper;
    private final RuntimeAgentExecutionService agentExecutionService;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper workflowVersionMapper;
    private final RuntimeGraphSpecExecutor graphSpecExecutor;
    private final RuntimeRunLifecycleService runLifecycleService;
    private final RuntimeTraceSpanMapper traceSpanMapper;
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
        RuntimeAgentEntity agent = agentMapper.selectById(version.getTargetId());
        RuntimeAgentConfigVersionEntity config = agentVersionMapper.selectById(version.getTargetVersionId());
        if (agent == null || config == null || !agent.getId().equals(config.getAgentId())
                || !Set.of("ACTIVE", "ARCHIVED").contains(RuntimeAutomationTypes.upper(config.getStatus()))) {
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
                    agent.getId(), config.getId(), input, true,
                    SupervisorRuntimeAdapter.SupervisorEventSink.NOOP, cancellation, identity);
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
        RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(version.getTargetId());
        RuntimeWorkflowVersionEntity published = workflowVersionMapper.selectById(version.getTargetVersionId());
        if (workflow == null || published == null || !workflow.getId().equals(published.getWorkflowId())
                || !Set.of("ACTIVE", "RETIRED").contains(RuntimeAutomationTypes.upper(published.getStatus()))
                || !StringUtils.hasText(published.getGraphSpecSnapshotJson())) {
            return ExecutionOutcome.failure(traceId, "AUTOMATION_TARGET_VERSION_UNAVAILABLE",
                    "Pinned Workflow version is unavailable", false);
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
                    published.getGraphSpecSnapshotJson(), input,
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
                                             RuntimeWorkflowDefinitionEntity workflow,
                                             RuntimeWorkflowVersionEntity version,
                                             Map<String, Object> input,
                                             WorkflowExecutionIdentity identity) {
        String spanId = "span_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        LocalDateTime now = LocalDateTime.now(java.time.Clock.systemUTC());
        RuntimeTraceSpanEntity root = new RuntimeTraceSpanEntity();
        root.setTraceId(traceId);
        root.setSpanId(spanId);
        root.setSpanType("WORKFLOW");
        root.setRuntimeType(workflow.getExecutionEngine());
        root.setAgentId(workflow.getId());
        root.setAgentName(workflow.getName());
        root.setNodeId(workflow.getId());
        root.setProjectCode(workflow.getProjectCode());
        root.setTenantId(identity.tenantId());
        root.setStatus("RUNNING");
        root.setInputSummary(json.write(WorkflowTraceSanitizer.sanitizeInputSummary(input)));
        root.setMetadataJson(json.write(Map.of(
                "sourceType", "AUTOMATION",
                "workflowId", workflow.getId(),
                "workflowVersionId", version.getId(),
                "workflowVersion", version.getVersion())));
        root.setStartedAt(now);
        root.setCreatedAt(now);
        traceSpanMapper.insert(root);
        runLifecycleService.beginPublishedWorkflow(
                traceId, spanId, "AUTOMATION", workflow, version, input, identity);
        return new WorkflowTrace(root.getId(), traceId, now);
    }

    private void finishWorkflowTrace(WorkflowTrace trace, RuntimeGraphSpecExecutionResult result) {
        LocalDateTime ended = LocalDateTime.now(java.time.Clock.systemUTC());
        RuntimeTraceSpanEntity root = traceSpanMapper.selectById(trace.rootId());
        if (root != null) {
            boolean waiting = result.isWaitingUser();
            root.setStatus(waiting ? "WAITING_USER" : (result.success() ? "SUCCESS" : "ERROR"));
            root.setOutputSummary(json.limit(WorkflowTraceSanitizer.sanitizeAnswer(result.answer()), 4000));
            root.setErrorCode(result.success() || waiting ? null : result.code());
            root.setErrorMessage(result.success() || waiting ? null : json.limit(result.answer(), 2000));
            root.setLatencyMs(waiting ? null : (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0, ChronoUnit.MILLIS.between(trace.startedAt(), ended))));
            root.setEndedAt(waiting ? null : ended);
            traceSpanMapper.updateById(root);
        }
        runLifecycleService.finishWorkflow(trace.traceId(), result.success(), result.code(), result.answer(),
                result.steps() == null ? 0 : result.steps().size(), result.metadata());
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

    private record WorkflowTrace(Long rootId, String traceId, LocalDateTime startedAt) {
    }
}
