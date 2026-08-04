package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeAgentExecutionService {

    private final RuntimeAgentExecutionContextResolver executionContextResolver;
    private final SupervisorRuntimeAdapter supervisorRuntime;
    private final SupervisorApprovalInteractionService approvalInteractionService;
    private final RuntimeInteractionResumeService interactionResumeService;
    private final RuntimeChatMemoryStore memoryStore;
    private final RuntimeRunLifecycleService runLifecycleService;

    public Map<String, Object> execute(Map<String, Object> request, boolean detailed) {
        return execute(request, detailed, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP, null);
    }

    public Map<String, Object> execute(Map<String, Object> request,
                                       boolean detailed,
                                       SupervisorRuntimeAdapter.SupervisorEventSink eventSink) {
        return execute(request, detailed, eventSink, RuntimeAgentExecutionCancellation.NOOP, null);
    }

    public Map<String, Object> execute(Map<String, Object> request,
                                       boolean detailed,
                                       SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                       RuntimeAgentExecutionCancellation cancellation) {
        return execute(request, detailed, eventSink, cancellation, null);
    }

    /**
     * @param trustedIdentity server-attested identity from Control internal contract only.
     *                        Public body fields (userId/externalUserId/globalUserId) never become trusted.
     */
    public Map<String, Object> execute(Map<String, Object> request,
                                       boolean detailed,
                                       SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                       RuntimeAgentExecutionCancellation cancellation,
                                       WorkflowExecutionIdentity trustedIdentity) {
        // Public / unauthenticated callers must never inject Control timings via body.
        return execute(request, detailed, eventSink, cancellation, trustedIdentity, null);
    }

    /**
     * @param trustedControlTiming Control observability timings extracted by the internal
     *                             authenticated controller only. Never read from public body.
     */
    public Map<String, Object> execute(Map<String, Object> request,
                                       boolean detailed,
                                       SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                       RuntimeAgentExecutionCancellation cancellation,
                                       WorkflowExecutionIdentity trustedIdentity,
                                       TrustedControlTiming trustedControlTiming) {
        Map<String, Object> body = normalizeContext(request);
        // Discard any client/body controlTiming — trust only the explicit parameter.
        body.remove("controlTiming");
        body.putIfAbsent("traceId", newTraceId());
        String interactionId = text(body.get("interactionId"));
        Map<String, Object> response;
        if (StringUtils.hasText(interactionId)
                && interactionId.startsWith(SupervisorApprovalInteractionService.INTERACTION_PREFIX)) {
            response = resumeSupervisorApproval(interactionId, body, detailed, eventSink, cancellation, trustedIdentity);
        } else if (StringUtils.hasText(interactionId)
                && interactionId.startsWith(WorkflowInteractionCodes.ID_PREFIX)) {
            response = resumeWorkflowInteraction(interactionId, body, detailed, eventSink, cancellation, trustedIdentity);
        } else {
            response = executeResolved(body, detailed, null, eventSink, cancellation, trustedIdentity);
        }
        mergeControlTiming(response, trustedControlTiming);
        return response;
    }

    /**
     * Replays an immutable published/archived Agent configuration. This path deliberately does
     * not resolve the Agent's current ACTIVE configuration.
     */
    public Map<String, Object> executePublishedConfig(String agentId,
                                                      Long configVersionId,
                                                      Map<String, Object> request,
                                                      boolean detailed) {
        Map<String, Object> body = normalizeContext(request);
        body.put("traceId", firstText(text(body.get("traceId")), newTraceId()));
        body.put("agentId", agentId);
        body.putIfAbsent("entryType", "REPLAY");
        Optional<RuntimeAgentExecutionContext> context =
                executionContextResolver.resolvePublished(agentId, configVersionId);
        if (context.isEmpty()) {
            return error("RUNTIME_AGENT_NOT_FOUND", "Agent not found: " + agentId,
                    null, body, detailed, List.of());
        }
        RuntimeAgentExecutionContext resolved = context.get();
        if (resolved.config() == null) {
            return error("RUNTIME_AGENT_CONFIG_NOT_PUBLISHED",
                    "Published Agent configuration not found: " + configVersionId,
                    resolved.agentView(), body, detailed, List.of());
        }
        return executeResolvedContext(body, detailed, null, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                RuntimeAgentExecutionCancellation.NOOP, resolved, true, null);
    }

    public void clearSession(String sessionId) {
        if (StringUtils.hasText(sessionId)) memoryStore.clear(sessionId.trim());
    }

    private Map<String, Object> resumeWorkflowInteraction(String interactionId,
                                                          Map<String, Object> body,
                                                          boolean detailed,
                                                          SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                                          RuntimeAgentExecutionCancellation cancellation,
                                                          WorkflowExecutionIdentity trustedIdentity) {
        RuntimeInteractionResumeService.Ownership ownership = new RuntimeInteractionResumeService.Ownership(
                firstText(text(body.get("appId")), text(body.get("projectCode"))),
                text(body.get("tenantId")),
                text(body.get("sessionId")),
                firstText(text(body.get("userId")), text(body.get("externalUserId")), text(body.get("globalUserId"))));
        Map<String, Object> resumeRequest = new LinkedHashMap<>(body);
        Map<String, Object> result = interactionResumeService.resume(interactionId, resumeRequest, ownership);
        String resumeCode = text(result.get("code"));
        if ("RUNTIME_INTERACTION_FORBIDDEN".equals(resumeCode)
                || "RUNTIME_INTERACTION_EXPIRED".equals(resumeCode)
                || "RUNTIME_INTERACTION_CONFLICT".equals(resumeCode)
                || "RUNTIME_INTERACTION_NOT_FOUND".equals(resumeCode)
                || "RUNTIME_INTERACTION_CANCELLED".equals(resumeCode)
                || "RUNTIME_INTERACTION_NOT_WAITING".equals(resumeCode)
                || "RUNTIME_INTERACTION_SESSION_REQUIRED".equals(resumeCode)
                || "RUNTIME_INTERACTION_GRAPH_MISSING".equals(resumeCode)) {
            return error(resumeCode, text(result.get("answer")), null, body, detailed, List.of());
        }
        boolean waiting = Boolean.TRUE.equals(result.get("waiting"))
                || WorkflowInteractionCodes.WAITING.equals(resumeCode);
        boolean success = !Boolean.FALSE.equals(result.get("success")) || waiting;
        String code = firstText(resumeCode,
                waiting ? WorkflowInteractionCodes.WAITING : "RUNTIME_GRAPH_EXECUTED");
        String answer = text(result.get("answer"));
        // Always prefer the interaction session's original traceId; never invent a new one for finish.
        String sessionTraceId = text(result.get("traceId"));
        if (!StringUtils.hasText(sessionTraceId)) {
            return error("RUNTIME_INTERACTION_TRACE_MISSING",
                    "Workflow interaction session is missing traceId", null, body, detailed, List.of());
        }
        String traceId = sessionTraceId;
        body.put("traceId", traceId);

        if (eventSink != null) {
            if (result.get("uiRequest") != null) {
                eventSink.emit("ui.requested", result.get("uiRequest"));
            }
            if (waiting) {
                eventSink.emit("turn.waiting", Map.of(
                        "interactionId", result.get("interactionId"),
                        "status", WorkflowExecutionStatus.SUSPENDED.name(),
                        "suspensionReason", "USER_INPUT",
                        "uiRequest", result.get("uiRequest")));
            }
        }

        String status = text(result.get("status"));
        Map<String, Object> continuation = result.get("continuation") instanceof Map<?, ?> rawCont
                ? new LinkedHashMap<>((Map<String, Object>) rawCont)
                : Map.of();
        boolean hasAgentContinuation = StringUtils.hasText(text(continuation.get("agentId")))
                && continuation.get("agentConfigVersionId") != null
                && "COMPLETED".equalsIgnoreCase(status)
                && success
                && !waiting;

        if (hasAgentContinuation) {
            return continueAfterWorkflowResume(
                    continuation, result, body, detailed, eventSink, cancellation, trustedIdentity);
        }

        if (StringUtils.hasText(traceId)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("interactionId", result.get("interactionId"));
            meta.put("sessionId", body.get("sessionId"));
            meta.put("runId", result.get("runId"));
            meta.put("sourceType", "WORKFLOW_INTERACTION_RESUME");
            // Waiting keeps root run open; success/fail/cancel closes the same trace.
            runLifecycleService.finishAgent(traceId, success && !waiting, code, answer, meta, null,
                    trustedIdentity);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", waiting || Boolean.TRUE.equals(result.get("success")));
        response.put("answer", answer);
        putIfPresent(response, "sessionId", text(body.get("sessionId")));
        putIfPresent(response, "runId", text(result.get("runId")));
        putIfPresent(response, "traceId", traceId);
        if (detailed && result.get("steps") != null) {
            response.put("steps", result.get("steps"));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", code);
        metadata.put("traceId", traceId);
        metadata.put("runId", result.get("runId"));
        metadata.put("interactionId", result.get("interactionId"));
        metadata.put("interactionPending", waiting);
        metadata.put("status", waiting
                ? WorkflowExecutionStatus.SUSPENDED.name()
                : WorkflowExecutionStatus.fromInteractionStatus(status).name());
        metadata.put("interactionStatus", status);
        if (result.get("metadata") instanceof Map<?, ?> rawMeta) {
            rawMeta.forEach((k, v) -> metadata.put(String.valueOf(k), v));
        }
        response.put("metadata", metadata);
        if (result.get("uiRequest") != null) {
            response.put("uiRequest", result.get("uiRequest"));
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> continueAfterWorkflowResume(Map<String, Object> continuation,
                                                            Map<String, Object> workflowResult,
                                                            Map<String, Object> body,
                                                            boolean detailed,
                                                            SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                                            RuntimeAgentExecutionCancellation cancellation,
                                                            WorkflowExecutionIdentity trustedIdentity) {
        String agentId = text(continuation.get("agentId"));
        Long configVersionId = null;
        Object rawConfigId = continuation.get("agentConfigVersionId");
        if (rawConfigId instanceof Number number) {
            configVersionId = number.longValue();
        } else if (rawConfigId != null) {
            try {
                configVersionId = Long.parseLong(String.valueOf(rawConfigId));
            } catch (NumberFormatException ignored) {
                configVersionId = null;
            }
        }
        if (configVersionId == null || !StringUtils.hasText(agentId)) {
            return error("RUNTIME_INTERACTION_CONTINUATION_INVALID",
                    "Workflow interaction continuation is missing Agent/config identity",
                    null, body, detailed, List.of());
        }
        Optional<RuntimeAgentExecutionContext> context =
                executionContextResolver.resolvePublished(agentId, configVersionId);
        if (context.isEmpty() || context.get().config() == null) {
            return error("RUNTIME_INTERACTION_CONTINUATION_INVALID",
                    "Workflow interaction continuation Agent config version not found",
                    context.map(RuntimeAgentExecutionContext::agentView).orElse(null), body, detailed, List.of());
        }
        RuntimeAgentExecutionContext resolved = context.get();
        RuntimeAgentView agent = resolved.agentView();
        RuntimeAgentConfigVersionEntity config = resolved.config();
        Map<String, Object> supervisorInput = new LinkedHashMap<>();
        Object original = continuation.get("originalInput");
        if (original instanceof Map<?, ?> rawOriginal) {
            supervisorInput.putAll((Map<String, Object>) rawOriginal);
        }
        supervisorInput.putAll(body);
        supervisorInput.put("traceId", text(continuation.get("traceId")));
        supervisorInput.put("agentId", agent.id());
        SupervisorRuntimeAdapter.SupervisorResult continued = supervisorRuntime.continueAfterWorkflowInteraction(
                continuation,
                workflowResult,
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agent, config, resolved.tools(), supervisorInput, null, eventSink, cancellation,
                        trustedIdentity, resolved.resolvedTargets()));
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("resume-workflow-interaction", text(workflowResult.get("interactionId"))));
        steps.add(step("continue-after-workflow", continued.code()));
        if (detailed && continued.steps() != null) {
            steps.addAll(continued.steps());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", continued.success());
        response.put("answer", continued.answer());
        putIfPresent(response, "sessionId", firstText(
                text(body.get("sessionId")),
                continued.metadata() == null ? null : text(continued.metadata().get("sessionId"))));
        putIfPresent(response, "traceId", continued.traceId());
        putIfPresent(response, "runId", text(workflowResult.get("runId")));
        if (detailed) {
            response.put("steps", steps);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", continued.code());
        metadata.put("agentId", agent.id());
        metadata.put("agentConfigVersionId", config.getId());
        metadata.put("traceId", continued.traceId());
        metadata.put("runId", workflowResult.get("runId"));
        metadata.put("interactionId", workflowResult.get("interactionId"));
        metadata.put("interactionPending", false);
        metadata.put("continuationConsumed", true);
        metadata.putAll(resolved.timingMetadata());
        if (continued.metadata() != null) {
            metadata.putAll(continued.metadata());
        }
        response.put("metadata", metadata);
        if (continued.uiRequest() != null) {
            response.put("uiRequest", continued.uiRequest());
        }
        return response;
    }

    private Map<String, Object> resumeSupervisorApproval(String interactionId,
                                                         Map<String, Object> submission,
                                                         boolean detailed,
                                                         SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                                         RuntimeAgentExecutionCancellation cancellation,
                                                         WorkflowExecutionIdentity trustedIdentity) {
        try {
            SupervisorApprovalInteractionService.ResumeDecision decision =
                    approvalInteractionService.prepareResume(interactionId, submission);
            Map<String, Object> originalInput = normalizeContext(decision.originalInput());
            String traceId = firstText(
                    text(originalInput.get("traceId")),
                    text(submission.get("traceId")),
                    newTraceId());
            if (decision.rejected()) {
                Map<String, Object> finishMetadata = new LinkedHashMap<>();
                finishMetadata.put("code", "SUPERVISOR_ACTION_REJECTED");
                finishMetadata.put("interactionId", interactionId);
                finishMetadata.put("interactionPending", false);
                putIfPresent(finishMetadata, "sessionId", firstText(
                        text(submission.get("sessionId")),
                        text(originalInput.get("sessionId"))));
                runLifecycleService.resumeAgent(traceId);
                runLifecycleService.finishAgent(traceId, false, "SUPERVISOR_ACTION_REJECTED",
                        decision.message(), finishMetadata, null, trustedIdentity);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("answer", decision.message());
                putIfPresent(response, "sessionId", text(submission.get("sessionId")));
                response.put("metadata", Map.of(
                        "code", "SUPERVISOR_ACTION_REJECTED",
                        "agentId", decision.agentId(),
                        "traceId", traceId,
                        "interactionId", interactionId,
                        "interactionPending", false));
                return response;
            }
            originalInput.put("agentId", decision.agentId());
            originalInput.put("traceId", traceId);
            originalInput.put("__resumeExistingTrace", true);
            return executeResolved(originalInput, detailed, decision.grant(), eventSink, cancellation, trustedIdentity);
        } catch (IllegalArgumentException ex) {
            return error("SUPERVISOR_APPROVAL_INVALID", ex.getMessage(), null,
                    submission, detailed, List.of());
        }
    }

    private Map<String, Object> executeResolved(Map<String, Object> body,
                                                boolean detailed,
                                                SupervisorRuntimeAdapter.PolicyApprovalGrant approvalGrant,
                                                SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                                RuntimeAgentExecutionCancellation cancellation,
                                                WorkflowExecutionIdentity trustedIdentity) {
        String agentLookup = firstText(
                text(body.get("agentId")),
                text(body.get("keySlug")));
        if (!StringUtils.hasText(agentLookup)) {
            return error("RUNTIME_AGENT_REQUIRED", "agentId is required", null, body, detailed, List.of());
        }

        Optional<RuntimeAgentExecutionContext> context = executionContextResolver.resolve(agentLookup);
        if (context.isEmpty()) {
            return error("RUNTIME_AGENT_NOT_FOUND", "Agent not found: " + agentLookup,
                    null, body, detailed, List.of());
        }
        return executeResolvedContext(body, detailed, approvalGrant, eventSink, cancellation,
                context.get(), false, trustedIdentity);
    }

    private Map<String, Object> executeResolvedContext(Map<String, Object> body,
                                                       boolean detailed,
                                                       SupervisorRuntimeAdapter.PolicyApprovalGrant approvalGrant,
                                                       SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                                       RuntimeAgentExecutionCancellation cancellation,
                                                       RuntimeAgentExecutionContext context,
                                                       boolean historicalReplay,
                                                       WorkflowExecutionIdentity trustedIdentity) {
        RuntimeAgentView agentView = context.agentView();
        List<Map<String, Object>> bootstrap = new ArrayList<>();
        bootstrap.add(step("resolve-agent", agentView.id()));
        if (!historicalReplay && !Boolean.TRUE.equals(agentView.enabled())) {
            return error("RUNTIME_AGENT_DISABLED", "Agent is disabled: " + agentView.keySlug(),
                    agentView, body, detailed, bootstrap);
        }
        RuntimeAgentConfigVersionEntity config = context.config();
        if (config == null) {
            return error("RUNTIME_AGENT_CONFIG_NOT_PUBLISHED",
                    "Agent has no published Supervisor configuration: " + agentView.keySlug(),
                    agentView, body, detailed, bootstrap);
        }
        bootstrap.add(step("resolve-agent-config", "v" + config.getVersionNo() + "#" + config.getId()));
        if (!"AGENTSCOPE".equalsIgnoreCase(config.getRuntimeType())) {
            return error("RUNTIME_AGENT_RUNTIME_UNSUPPORTED",
                    "Published Agent runtimeType must be AGENTSCOPE", agentView, body, detailed, bootstrap);
        }

        List<RuntimeAgentWorkflowToolEntity> tools = context.tools() == null ? List.of() : context.tools();
        bootstrap.add(step("resolve-workflow-tools", String.valueOf(tools.size())));
        SupervisorRuntimeAdapter.SupervisorResult result = supervisorRuntime.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(
                        agentView, config, tools, body, approvalGrant, eventSink, cancellation,
                        trustedIdentity, context.resolvedTargets()));
        List<Map<String, Object>> steps = new ArrayList<>(bootstrap);
        if (result.steps() != null) steps.addAll(result.steps());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success());
        response.put("answer", result.answer());
        putIfPresent(response, "sessionId", firstText(
                text(body.get("sessionId")),
                result.metadata() == null ? null : text(result.metadata().get("sessionId"))));
        putIfPresent(response, "intentType", text(body.get("intentHint")));
        if (detailed) response.put("steps", steps);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", result.code());
        metadata.put("agentId", agentView.id());
        metadata.put("agentKey", agentView.keySlug());
        metadata.put("projectCode", agentView.projectCode());
        metadata.put("agentConfigVersionId", config.getId());
        metadata.put("agentConfigVersion", config.getVersionNo());
        metadata.put("runtimeType", "AGENTSCOPE");
        metadata.put("traceId", result.traceId());
        metadata.putAll(context.timingMetadata());
        if (result.metadata() != null) metadata.putAll(result.metadata());
        response.put("metadata", metadata);
        if (result.uiRequest() != null) response.put("uiRequest", result.uiRequest());
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeContext(Map<String, Object> request) {
        Map<String, Object> body = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        Map<String, Object> metadata = body.get("metadata") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
        // Clients must not forge Control-owned timing fields via body or metadata.
        body.remove("controlTiming");
        stripForgedControlKeys(body);
        stripForgedControlKeys(metadata);
        for (String key : List.of("tenantId", "appId", "externalUserId", "globalUserId",
                "projectCode", "roles", "pageInstanceId", "origin", "entryType", "replayOfTraceId")) {
            if (!body.containsKey(key) && metadata.containsKey(key)) body.put(key, metadata.get(key));
            if (!metadata.containsKey(key) && body.containsKey(key)) metadata.put(key, body.get(key));
        }
        // Always replace metadata so stripped forged control.* keys cannot linger on the original map.
        if (metadata.isEmpty()) {
            body.remove("metadata");
        } else {
            body.put("metadata", metadata);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private void mergeControlTiming(Map<String, Object> response, TrustedControlTiming controlTiming) {
        if (response == null || controlTiming == null || controlTiming.isEmpty()) {
            return;
        }
        Object rawMeta = response.get("metadata");
        Map<String, Object> metadata = rawMeta instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
        metadata.putAll(controlTiming.toMetadataEntries());
        response.put("metadata", metadata);
    }

    private void stripForgedControlKeys(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        map.keySet().removeIf(key -> key != null && (key.startsWith("control.") || "controlTiming".equals(key)));
    }

    private Map<String, Object> error(String code,
                                      String answer,
                                      RuntimeAgentView agent,
                                      Map<String, Object> request,
                                      boolean detailed,
                                      List<Map<String, Object>> steps) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("answer", answer);
        putIfPresent(response, "sessionId", text(request.get("sessionId")));
        if (detailed && steps != null && !steps.isEmpty()) response.put("steps", steps);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("code", code);
        metadata.put("traceId", text(request.get("traceId")));
        if (agent != null) {
            metadata.put("agentId", agent.id());
            metadata.put("agentKey", agent.keySlug());
            metadata.put("projectCode", agent.projectCode());
        }
        response.put("metadata", metadata);
        runLifecycleService.rejectAgent(text(request.get("traceId")), agent, request, code, answer);
        return response;
    }

    private Map<String, Object> step(String name, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("detail", detail);
        return step;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return null;
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
