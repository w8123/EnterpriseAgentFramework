package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowToolEntity;
import com.enterprise.ai.runtime.chat.RuntimeChatMemoryStore;
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

    private final RuntimeAgentService agentService;
    private final RuntimeAgentConfigService configService;
    private final SupervisorRuntimeAdapter supervisorRuntime;
    private final SupervisorApprovalInteractionService approvalInteractionService;
    private final RuntimeChatMemoryStore memoryStore;
    private final RuntimeRunLifecycleService runLifecycleService;

    public Map<String, Object> execute(Map<String, Object> request, boolean detailed) {
        return execute(request, detailed, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP);
    }

    public Map<String, Object> execute(Map<String, Object> request,
                                       boolean detailed,
                                       SupervisorRuntimeAdapter.SupervisorEventSink eventSink) {
        Map<String, Object> body = normalizeContext(request);
        body.putIfAbsent("traceId", newTraceId());
        String interactionId = text(body.get("interactionId"));
        if (StringUtils.hasText(interactionId)
                && interactionId.startsWith(SupervisorApprovalInteractionService.INTERACTION_PREFIX)) {
            return resumeSupervisorApproval(interactionId, body, detailed, eventSink);
        }
        return executeResolved(body, detailed, null, eventSink);
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
        Optional<RuntimeAgentView> agent = agentService.findByIdOrKeySlug(agentId);
        if (agent.isEmpty()) {
            return error("RUNTIME_AGENT_NOT_FOUND", "Agent not found: " + agentId,
                    null, body, detailed, List.of());
        }
        Optional<RuntimeAgentConfigVersionEntity> selected = configService.find(configVersionId);
        if (selected.isEmpty() || !agent.get().id().equals(selected.get().getAgentId())
                || "DRAFT".equalsIgnoreCase(selected.get().getStatus())) {
            return error("RUNTIME_AGENT_CONFIG_NOT_PUBLISHED",
                    "Published Agent configuration not found: " + configVersionId,
                    agent.get(), body, detailed, List.of());
        }
        return executeResolvedConfig(body, detailed, null, SupervisorRuntimeAdapter.SupervisorEventSink.NOOP,
                agent.get(), selected.get(), true);
    }

    public void clearSession(String sessionId) {
        if (StringUtils.hasText(sessionId)) memoryStore.clear(sessionId.trim());
    }

    private Map<String, Object> resumeSupervisorApproval(String interactionId,
                                                         Map<String, Object> submission,
                                                         boolean detailed,
                                                         SupervisorRuntimeAdapter.SupervisorEventSink eventSink) {
        try {
            SupervisorApprovalInteractionService.ResumeDecision decision =
                    approvalInteractionService.prepareResume(interactionId, submission);
            if (decision.rejected()) {
                runLifecycleService.rejectAgent(text(submission.get("traceId")), null, submission,
                        "SUPERVISOR_ACTION_REJECTED", decision.message());
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("answer", decision.message());
                putIfPresent(response, "sessionId", text(submission.get("sessionId")));
                response.put("metadata", Map.of(
                        "code", "SUPERVISOR_ACTION_REJECTED",
                        "agentId", decision.agentId(),
                        "traceId", text(submission.get("traceId")),
                        "interactionId", interactionId,
                        "interactionPending", false));
                return response;
            }
            Map<String, Object> originalInput = normalizeContext(decision.originalInput());
            originalInput.put("agentId", decision.agentId());
            originalInput.put("traceId", firstText(text(submission.get("traceId")), newTraceId()));
            return executeResolved(originalInput, detailed, decision.grant(), eventSink);
        } catch (IllegalArgumentException ex) {
            return error("SUPERVISOR_APPROVAL_INVALID", ex.getMessage(), null,
                    submission, detailed, List.of());
        }
    }

    private Map<String, Object> executeResolved(Map<String, Object> body,
                                                boolean detailed,
                                                SupervisorRuntimeAdapter.PolicyApprovalGrant approvalGrant,
                                                SupervisorRuntimeAdapter.SupervisorEventSink eventSink) {
        String agentLookup = firstText(
                text(body.get("agentId")),
                text(body.get("keySlug")));
        if (!StringUtils.hasText(agentLookup)) {
            return error("RUNTIME_AGENT_REQUIRED", "agentId is required", null, body, detailed, List.of());
        }

        Optional<RuntimeAgentView> agent = agentService.findByIdOrKeySlug(agentLookup);
        if (agent.isEmpty()) {
            return error("RUNTIME_AGENT_NOT_FOUND", "Agent not found: " + agentLookup,
                    null, body, detailed, List.of());
        }
        RuntimeAgentView agentView = agent.get();
        List<Map<String, Object>> bootstrap = new ArrayList<>();
        bootstrap.add(step("resolve-agent", agentView.id()));
        if (!Boolean.TRUE.equals(agentView.enabled())) {
            return error("RUNTIME_AGENT_DISABLED", "Agent is disabled: " + agentView.keySlug(),
                    agentView, body, detailed, bootstrap);
        }

        Optional<RuntimeAgentConfigVersionEntity> activeConfig = configService.resolveActive(agentView.id());
        if (activeConfig.isEmpty()) {
            return error("RUNTIME_AGENT_CONFIG_NOT_PUBLISHED",
                    "Agent has no published Supervisor configuration: " + agentView.keySlug(),
                    agentView, body, detailed, bootstrap);
        }
        return executeResolvedConfig(body, detailed, approvalGrant, eventSink,
                agentView, activeConfig.get(), false);
    }

    private Map<String, Object> executeResolvedConfig(Map<String, Object> body,
                                                      boolean detailed,
                                                      SupervisorRuntimeAdapter.PolicyApprovalGrant approvalGrant,
                                                      SupervisorRuntimeAdapter.SupervisorEventSink eventSink,
                                                      RuntimeAgentView agentView,
                                                      RuntimeAgentConfigVersionEntity config,
                                                      boolean historicalReplay) {
        List<Map<String, Object>> bootstrap = new ArrayList<>();
        bootstrap.add(step("resolve-agent", agentView.id()));
        if (!historicalReplay && !Boolean.TRUE.equals(agentView.enabled())) {
            return error("RUNTIME_AGENT_DISABLED", "Agent is disabled: " + agentView.keySlug(),
                    agentView, body, detailed, bootstrap);
        }
        bootstrap.add(step("resolve-agent-config", "v" + config.getVersionNo() + "#" + config.getId()));
        if (!"AGENTSCOPE".equalsIgnoreCase(config.getRuntimeType())) {
            return error("RUNTIME_AGENT_RUNTIME_UNSUPPORTED",
                    "Published Agent runtimeType must be AGENTSCOPE", agentView, body, detailed, bootstrap);
        }

        List<RuntimeAgentWorkflowToolEntity> tools = configService.resolveTools(agentView.id(), config);
        bootstrap.add(step("resolve-workflow-tools", String.valueOf(tools.size())));
        SupervisorRuntimeAdapter.SupervisorResult result = supervisorRuntime.execute(
                new SupervisorRuntimeAdapter.SupervisorRequest(agentView, config, tools, body, approvalGrant, eventSink));
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
        for (String key : List.of("tenantId", "appId", "externalUserId", "globalUserId",
                "projectCode", "roles", "pageInstanceId", "origin", "entryType", "replayOfTraceId")) {
            if (!body.containsKey(key) && metadata.containsKey(key)) body.put(key, metadata.get(key));
            if (!metadata.containsKey(key) && body.containsKey(key)) metadata.put(key, body.get(key));
        }
        if (!metadata.isEmpty()) body.put("metadata", metadata);
        return body;
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
