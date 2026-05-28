package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeSelector;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchRequest;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchResult;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchService;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.service.IntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared entry for agent routing. Controller, A2A and service calls reuse this
 * facade while concrete execution is delegated to runtime adapters or managed
 * embedded runtime instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private static final String GENERAL_CHAT = "GENERAL_CHAT";
    private static final String CENTRAL = "CENTRAL";
    private static final String EMBEDDED = "EMBEDDED";
    private static final String HYBRID = "HYBRID";

    private final IntentService intentService;
    private final AgentDefinitionService agentDefinitionService;
    private final AgentRuntimeSelector runtimeSelector;
    private final EmbeddedRuntimeDispatchService embeddedRuntimeDispatchService;
    private final GuardDecisionLogService guardDecisionLogService;

    public AgentResult executeByDefinition(AgentDefinition definition, String sessionId,
                                           String userId, String message) {
        return executeByDefinition(definition, sessionId, userId, message, null);
    }

    public AgentResult executeByDefinition(AgentDefinition definition, String sessionId,
                                           String userId, String message, List<String> roles) {
        return executeByDefinition(definition, sessionId, userId, message, roles, null);
    }

    public AgentResult executeByDefinition(AgentDefinition definition, String sessionId,
                                           String userId, String message, List<String> roles,
                                           Map<String, Object> metadata) {
        String traceId = UUID.randomUUID().toString();
        String intentType = definition.getIntentType();
        log.info("[AgentRouter] Execute definition: agent={}, keySlug={}, runtime={}, placement={}, sessionId={}, roles={}, traceId={}",
                definition.getName(), definition.getKeySlug(), runtimeTypeOf(definition),
                runtimePlacementOf(definition), sessionId, roles, traceId);

        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .roles(roles == null ? List.of() : roles)
                .message(message)
                .intentType(intentType)
                .agentDefinition(definition)
                .metadata(metadata)
                .build();
        AgentResult denied = denyIfAgentRoleNotAllowed(request, intentType);
        if (denied != null) {
            return denied;
        }
        return executeRuntime(request, intentType);
    }

    public AgentResult route(String sessionId, String userId, String message, String intentHint) {
        return route(sessionId, userId, message, intentHint, null);
    }

    public AgentResult route(String sessionId, String userId, String message, String intentHint,
                             List<String> roles) {
        String intentType = resolveIntent(message, intentHint);
        String traceId = UUID.randomUUID().toString();
        AgentDefinition definition = agentDefinitionService.findByIntentType(intentType)
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled Agent definition found for intent '" + intentType + "'"));

        log.info("[AgentRouter] Route decision: intent={}, runtime={}, placement={}, sessionId={}, userId={}, roles={}, traceId={}",
                intentType, runtimeTypeOf(definition), runtimePlacementOf(definition), sessionId, userId, roles, traceId);

        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .roles(roles == null ? List.of() : roles)
                .message(message)
                .intentType(intentType)
                .agentDefinition(definition)
                .build();
        AgentResult denied = denyIfAgentRoleNotAllowed(request, intentType);
        if (denied != null) {
            return denied;
        }
        return executeRuntime(request, intentType);
    }

    private AgentResult denyIfAgentRoleNotAllowed(AgentRuntimeRequest request, String intentType) {
        AgentDefinition definition = request.getAgentDefinition();
        List<String> allowedRoles = definition == null || definition.getAllowedRoles() == null
                ? List.of()
                : definition.getAllowedRoles().stream()
                .filter(StringUtils::hasText)
                .toList();
        if (allowedRoles.isEmpty()) {
            return null;
        }
        Set<String> userRoles = (request.getRoles() == null ? List.<String>of() : request.getRoles()).stream()
                .filter(StringUtils::hasText)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean allowed = allowedRoles.stream()
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .anyMatch(userRoles::contains);
        if (allowed) {
            return null;
        }
        String target = agentKeyOf(definition);
        Map<String, Object> metadata = new HashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put("intentType", intentType);
        metadata.put("traceId", request.getTraceId());
        metadata.put("decisionType", "AGENT_RBAC");
        metadata.put("allowedRoles", allowedRoles);
        metadata.put("roles", request.getRoles() == null ? List.of() : request.getRoles());
        guardDecisionLogService.record(
                request.getTraceId(),
                "AGENT_RBAC",
                "AGENT",
                target,
                "DENY",
                "user role is not allowed",
                metadata);
        return AgentResult.builder()
                .success(false)
                .answer("Agent execution denied: user role is not allowed")
                .metadata(metadata)
                .build();
    }

    private AgentResult executeRuntime(AgentRuntimeRequest request, String intentType) {
        AgentDefinition definition = request.getAgentDefinition();
        String placement = runtimePlacementOf(definition);
        if (EMBEDDED.equals(placement)) {
            return executeEmbedded(request, intentType, false);
        }
        if (HYBRID.equals(placement)) {
            AgentResult embedded = executeEmbedded(request, intentType, true);
            if (embedded.isSuccess()) {
                return embedded;
            }
            Map<String, Object> metadata = request.getMetadata() == null ? new HashMap<>() : new HashMap<>(request.getMetadata());
            if (embedded.getMetadata() != null && embedded.getMetadata().get("embeddedFallbackReason") != null) {
                metadata.put("embeddedFallbackReason", embedded.getMetadata().get("embeddedFallbackReason"));
            }
            request.setMetadata(metadata);
        }
        try {
            AgentRuntimeAdapter adapter = runtimeSelector.select(request);
            AgentRuntimeResult result = adapter.execute(request);
            return toAgentResult(result, intentType, request.getAgentDefinition(), request.getMetadata());
        } catch (Exception e) {
            log.error("[AgentRouter] Agent execution failed: intent={}, traceId={}", intentType, request.getTraceId(), e);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("intentType", intentType);
            metadata.put("traceId", request.getTraceId());
            metadata.put("runtimeType", runtimeTypeOf(request.getAgentDefinition()));
            metadata.put("runtimePlacement", placement);
            return AgentResult.builder()
                    .success(false)
                    .answer("Agent execution failed: " + e.getMessage())
                    .metadata(metadata)
                    .build();
        }
    }

    private AgentResult executeEmbedded(AgentRuntimeRequest request, String intentType, boolean allowCentralFallback) {
        AgentDefinition definition = request.getAgentDefinition();
        EmbeddedTarget target = embeddedTarget(definition);
        if (target == null) {
            if (allowCentralFallback) {
                return AgentResult.builder()
                        .success(false)
                        .answer("Embedded Runtime target is not configured, fallback to central runtime")
                        .metadata(Map.of("embeddedFallbackReason", "missing-target"))
                        .build();
            }
            return embeddedFailure(request, intentType, "MISSING_EMBEDDED_TARGET",
                    "Embedded Runtime target is not configured");
        }

        try {
            EmbeddedRuntimeDispatchResult dispatch = embeddedRuntimeDispatchService.dispatch(new EmbeddedRuntimeDispatchRequest(
                    target.projectCode(),
                    target.instanceId(),
                    agentKeyOf(definition),
                    request.getMessage(),
                    request.getSessionId(),
                    request.getUserId(),
                    Map.of(
                            "roles", request.getRoles() == null ? List.of() : request.getRoles(),
                            "intentType", intentType,
                            "traceId", request.getTraceId()
                    ),
                    definition.getGraphSpec() == null ? Map.of() : Map.of("graphSpec", definition.getGraphSpec())
            ));
            return toAgentResult(dispatch, request, intentType);
        } catch (Exception ex) {
            log.warn("[AgentRouter] Embedded Runtime dispatch failed: agent={}, traceId={}, fallback={}",
                    definition == null ? null : definition.getName(), request.getTraceId(), allowCentralFallback, ex);
            if (allowCentralFallback) {
                return AgentResult.builder()
                        .success(false)
                        .answer("Embedded Runtime dispatch failed, fallback to central runtime: " + ex.getMessage())
                        .metadata(Map.of("embeddedFallbackReason", ex.getMessage()))
                        .build();
            }
            return embeddedFailure(request, intentType, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private AgentResult toAgentResult(EmbeddedRuntimeDispatchResult dispatch,
                                      AgentRuntimeRequest request,
                                      String intentType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("intentType", intentType);
        metadata.put("traceId", request.getTraceId());
        metadata.put("runtimePlacement", EMBEDDED);
        metadata.put("projectCode", dispatch.projectCode());
        metadata.put("instanceId", dispatch.instanceId());
        metadata.put("dispatchUrl", dispatch.dispatchUrl());
        if (dispatch.metadata() != null) {
            metadata.putAll(dispatch.metadata());
        }
        if (dispatch.errorCode() != null) {
            metadata.put("errorCode", dispatch.errorCode());
        }
        if (dispatch.errorMessage() != null) {
            metadata.put("errorMessage", dispatch.errorMessage());
        }
        return AgentResult.builder()
                .success(dispatch.success())
                .answer(dispatch.answer())
                .steps(toStepRecords(dispatch.steps()))
                .metadata(metadata)
                .build();
    }

    private AgentResult embeddedFailure(AgentRuntimeRequest request,
                                        String intentType,
                                        String errorCode,
                                        String errorMessage) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("intentType", intentType);
        metadata.put("traceId", request.getTraceId());
        metadata.put("runtimePlacement", runtimePlacementOf(request.getAgentDefinition()));
        metadata.put("errorCode", errorCode);
        metadata.put("errorMessage", errorMessage);
        return AgentResult.builder()
                .success(false)
                .answer("Embedded Runtime execution failed: " + errorMessage)
                .metadata(metadata)
                .build();
    }

    private AgentResult toAgentResult(AgentRuntimeResult runtimeResult,
                                      String intentType,
                                      AgentDefinition definition,
                                      Map<String, Object> requestMetadata) {
        Map<String, Object> metadata = new HashMap<>();
        if (requestMetadata != null) {
            metadata.putAll(requestMetadata);
        }
        if (runtimeResult.getMetadata() != null) {
            metadata.putAll(runtimeResult.getMetadata());
        }
        metadata.putIfAbsent("intentType", intentType);
        metadata.putIfAbsent("traceId", runtimeResult.getTraceId());
        metadata.putIfAbsent("runtimeType", runtimeResult.getRuntimeType());
        metadata.putIfAbsent("runtimePlacement", runtimePlacementOf(definition));
        if (definition != null && definition.getExtra() != null) {
            metadata.putIfAbsent("version", definition.getExtra().get("__version"));
            metadata.putIfAbsent("versionId", definition.getExtra().get("__versionId"));
        }
        if (runtimeResult.getAgentName() != null) {
            metadata.putIfAbsent("agentName", runtimeResult.getAgentName());
        }
        if (runtimeResult.getToolCalls() != null && !runtimeResult.getToolCalls().isEmpty()) {
            metadata.putIfAbsent("toolCalls", runtimeResult.getToolCalls());
        }
        if (runtimeResult.getSteps() != null && !runtimeResult.getSteps().isEmpty()) {
            metadata.putIfAbsent("steps", runtimeResult.getSteps());
        }

        return AgentResult.builder()
                .success(runtimeResult.isSuccess())
                .answer(runtimeResult.getAnswer())
                .steps(toStepRecords(runtimeResult.getSteps()))
                .metadata(metadata)
                .uiRequest(runtimeResult.getUiRequest())
                .build();
    }

    private List<AgentResult.StepRecord> toStepRecords(List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<AgentResult.StepRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            records.add(new AgentResult.StepRecord("Step " + (i + 1), steps.get(i)));
        }
        return records;
    }

    private String resolveIntent(String message, String intentHint) {
        String raw;
        if (intentHint != null && !intentHint.isBlank()) {
            raw = intentHint;
        } else {
            raw = intentService.recognizeIntent(message);
        }

        if (agentDefinitionService.findByIntentType(raw).isEmpty()) {
            log.warn("[AgentRouter] Intent '{}' has no enabled Agent definition, fallback to {}", raw, GENERAL_CHAT);
            return GENERAL_CHAT;
        }
        return raw;
    }

    private String runtimeTypeOf(AgentDefinition definition) {
        if (definition != null && definition.getRuntimeType() != null && !definition.getRuntimeType().isBlank()) {
            return definition.getRuntimeType();
        }
        return AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE;
    }

    private String runtimePlacementOf(AgentDefinition definition) {
        if (definition != null && definition.getRuntimePlacement() != null && !definition.getRuntimePlacement().isBlank()) {
            return definition.getRuntimePlacement().trim().toUpperCase();
        }
        return CENTRAL;
    }

    private EmbeddedTarget embeddedTarget(AgentDefinition definition) {
        if (definition == null || definition.getRuntimeConfig() == null) {
            return null;
        }
        Object raw = definition.getRuntimeConfig().get("embeddedRuntime");
        if (!(raw instanceof Map<?, ?>)) {
            raw = definition.getRuntimeConfig().get("runtimeInstance");
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String projectCode = stringValue(map.get("projectCode"));
        String instanceId = stringValue(map.get("instanceId"));
        if (projectCode == null || projectCode.isBlank() || instanceId == null || instanceId.isBlank()) {
            return null;
        }
        return new EmbeddedTarget(projectCode, instanceId);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String agentKeyOf(AgentDefinition definition) {
        if (definition == null) {
            return null;
        }
        if (definition.getKeySlug() != null && !definition.getKeySlug().isBlank()) {
            return definition.getKeySlug();
        }
        if (definition.getId() != null && !definition.getId().isBlank()) {
            return definition.getId();
        }
        return definition.getName();
    }

    private record EmbeddedTarget(String projectCode, String instanceId) {
    }
}
