package com.enterprise.ai.agent.agentscope;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeAdapter;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeResult;
import com.enterprise.ai.agent.runtime.AgentRuntimeSelector;
import com.enterprise.ai.agent.runtime.GraphRuntimeContext;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchRequest;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchResult;
import com.enterprise.ai.agent.runtime.EmbeddedRuntimeDispatchService;
import com.enterprise.ai.agent.governance.GuardDecisionLogService;
import com.enterprise.ai.agent.service.IntentService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingService;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeGraphAdapter;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRouter {

    private static final String GENERAL_CHAT = "GENERAL_CHAT";
    private static final String CENTRAL = "CENTRAL";
    private static final String EMBEDDED = "EMBEDDED";
    private static final String HYBRID = "HYBRID";

    private final IntentService intentService;
    private final AgentEntryService agentEntryService;
    private final AgentWorkflowBindingService bindingService;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowRuntimeGraphAdapter workflowRuntimeGraphAdapter;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeSelector runtimeSelector;
    private final EmbeddedRuntimeDispatchService embeddedRuntimeDispatchService;
    private final GuardDecisionLogService guardDecisionLogService;

    public AgentResult executeByProfile(AgentRuntimeProfile profile, String sessionId,
                                        String userId, String message) {
        return executeByProfile(profile, sessionId, userId, message, null);
    }

    public AgentResult executeByProfile(AgentRuntimeProfile profile, String sessionId,
                                        String userId, String message, List<String> roles) {
        return executeByProfile(profile, sessionId, userId, message, roles, null);
    }

    public AgentResult executeByProfile(AgentRuntimeProfile profile, String sessionId,
                                        String userId, String message, List<String> roles,
                                        Map<String, Object> metadata) {
        String traceId = UUID.randomUUID().toString();
        String intentType = profile.getIntentType();
        log.info("[AgentRouter] Execute profile: agent={}, keySlug={}, runtime={}, placement={}, sessionId={}, roles={}, traceId={}",
                profile.getName(), profile.getKeySlug(), profile.getRuntimeType(),
                profile.getRuntimePlacement(), sessionId, roles, traceId);

        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .roles(roles == null ? List.of() : roles)
                .message(message)
                .intentType(intentType)
                .agentRuntimeProfile(profile)
                .metadata(metadata)
                .build();
        AgentResult denied = denyIfAgentRoleNotAllowed(request, intentType, profile.getAllowedRoles(), agentKeyOf(profile));
        if (denied != null) {
            return denied;
        }
        return executeRuntime(request, intentType);
    }

    public AgentResult executeByGraphSpec(GraphSpec graphSpec,
                                          GraphRuntimeContext runtimeContext,
                                          String sessionId,
                                          String userId,
                                          String message) {
        return executeByGraphSpec(graphSpec, runtimeContext, sessionId, userId, message, null, null);
    }

    public AgentResult executeByGraphSpec(GraphSpec graphSpec,
                                          GraphRuntimeContext runtimeContext,
                                          String sessionId,
                                          String userId,
                                          String message,
                                          List<String> roles,
                                          Map<String, Object> metadata) {
        if (graphSpec == null || runtimeContext == null) {
            throw new IllegalArgumentException("graphSpec and runtimeContext are required");
        }
        Map<String, Object> safeMetadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        String traceId = stringValue(safeMetadata.get("traceId"));
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            safeMetadata.put("traceId", traceId);
        }
        String intentType = firstNonBlank(runtimeContext.getIntentType(), "WORKFLOW");
        log.info("[AgentRouter] Execute graph: name={}, sourceType={}, sourceId={}, runtime={}, placement={}, sessionId={}, roles={}, traceId={}",
                runtimeContext.getName(),
                runtimeContext.getSourceType(),
                runtimeContext.getSourceId(),
                firstNonBlank(runtimeContext.getRuntimeType(), AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE),
                firstNonBlank(runtimeContext.getRuntimePlacement(), CENTRAL),
                sessionId,
                roles,
                traceId);

        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .roles(roles == null ? List.of() : roles)
                .message(message)
                .intentType(intentType)
                .graphSpec(graphSpec)
                .graphRuntimeContext(runtimeContext)
                .metadata(safeMetadata)
                .build();
        return executeRuntime(request, intentType);
    }

    public AgentResult route(String sessionId, String userId, String message, String intentHint) {
        return route(sessionId, userId, message, intentHint, null);
    }

    public AgentResult route(String sessionId, String userId, String message, String intentHint,
                             List<String> roles) {
        String intentType = resolveIntent(message, intentHint);
        String traceId = UUID.randomUUID().toString();

        List<AgentWorkflowBindingEntity> bindings = bindingService.listEnabledByIntentType(intentType);
        if (!bindings.isEmpty()) {
            AgentWorkflowBindingEntity binding = bindings.get(0);
            AgentEntryEntity agent = agentEntryService.findById(binding.getAgentId())
                    .orElseThrow(() -> new IllegalStateException("Agent entry not found: " + binding.getAgentId()));
            WorkflowDefinitionEntity workflow = workflowDefinitionService.findById(binding.getWorkflowId())
                    .orElseThrow(() -> new IllegalStateException("Workflow not found: " + binding.getWorkflowId()));
            WorkflowVersionEntity version = workflowVersionService.resolveActive(workflow.getId());
            WorkflowRuntimeGraphAdapter.RuntimeGraph graph = workflowRuntimeGraphAdapter.toRuntimeGraph(
                    agent, workflow, version,
                    WorkflowRuntimeGraphAdapter.RuntimeContextOptions.builder().binding(binding).build());
            Map<String, Object> metadata = Map.of("traceId", traceId, "intentType", intentType);
            return executeByGraphSpec(graph.graphSpec(), graph.runtimeContext(), sessionId, userId, message, roles, metadata);
        }

        AgentEntryEntity entry = agentEntryService.findByIntentType(intentType)
                .orElseThrow(() -> new IllegalStateException(
                        "No enabled Agent entry found for intent '" + intentType + "'"));
        AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
        log.info("[AgentRouter] Route decision: intent={}, runtime={}, placement={}, sessionId={}, userId={}, roles={}, traceId={}",
                intentType, profile.getRuntimeType(), profile.getRuntimePlacement(), sessionId, userId, roles, traceId);

        AgentRuntimeRequest request = AgentRuntimeRequest.builder()
                .traceId(traceId)
                .sessionId(sessionId)
                .userId(userId)
                .roles(roles == null ? List.of() : roles)
                .message(message)
                .intentType(intentType)
                .agentRuntimeProfile(profile)
                .build();
        AgentResult denied = denyIfAgentRoleNotAllowed(request, intentType, profile.getAllowedRoles(), agentKeyOf(profile));
        if (denied != null) {
            return denied;
        }
        return executeRuntime(request, intentType);
    }

    private AgentResult denyIfAgentRoleNotAllowed(AgentRuntimeRequest request,
                                                  String intentType,
                                                  List<String> allowedRoles,
                                                  String agentKey) {
        if (request.getGraphRuntimeContext() != null && request.getAgentRuntimeProfile() == null) {
            return null;
        }
        List<String> roles = allowedRoles == null ? List.of() : allowedRoles.stream()
                .filter(StringUtils::hasText)
                .toList();
        if (roles.isEmpty()) {
            return null;
        }
        Set<String> userRoles = (request.getRoles() == null ? List.<String>of() : request.getRoles()).stream()
                .filter(StringUtils::hasText)
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        boolean allowed = roles.stream()
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .anyMatch(userRoles::contains);
        if (allowed) {
            return null;
        }
        Map<String, Object> metadata = new HashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put("intentType", intentType);
        metadata.put("traceId", request.getTraceId());
        metadata.put("decisionType", "AGENT_RBAC");
        metadata.put("allowedRoles", roles);
        metadata.put("roles", request.getRoles() == null ? List.of() : request.getRoles());
        guardDecisionLogService.record(
                request.getTraceId(),
                "AGENT_RBAC",
                "AGENT",
                agentKey,
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
        String placement = runtimePlacementOf(request);
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
            return toAgentResult(result, intentType, request, request.getMetadata());
        } catch (Exception e) {
            log.error("[AgentRouter] Agent execution failed: intent={}, traceId={}", intentType, request.getTraceId(), e);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("intentType", intentType);
            metadata.put("traceId", request.getTraceId());
            metadata.put("runtimeType", runtimeTypeOf(request));
            metadata.put("runtimePlacement", placement);
            return AgentResult.builder()
                    .success(false)
                    .answer("Agent execution failed: " + e.getMessage())
                    .metadata(metadata)
                    .build();
        }
    }

    private AgentResult executeEmbedded(AgentRuntimeRequest request, String intentType, boolean allowCentralFallback) {
        GraphRuntimeContext runtimeContext = request.getGraphRuntimeContext();
        AgentRuntimeProfile profile = request.getAgentRuntimeProfile();
        EmbeddedTarget target = embeddedTarget(runtimeContext, profile);
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
                    agentKeyOf(runtimeContext, profile),
                    request.getMessage(),
                    request.getSessionId(),
                    request.getUserId(),
                    Map.of(
                            "roles", request.getRoles() == null ? List.of() : request.getRoles(),
                            "intentType", intentType,
                            "traceId", request.getTraceId()
                    ),
                    request.getGraphSpec() == null ? Map.of() : Map.of("graphSpec", request.getGraphSpec())
            ));
            return toAgentResult(dispatch, request, intentType);
        } catch (Exception ex) {
            log.warn("[AgentRouter] Embedded Runtime dispatch failed: agent={}, traceId={}, fallback={}",
                    runtimeContext == null ? (profile == null ? null : profile.getName()) : runtimeContext.getName(),
                    request.getTraceId(), allowCentralFallback, ex);
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
        metadata.put("runtimePlacement", runtimePlacementOf(request));
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
                                      AgentRuntimeRequest request,
                                      Map<String, Object> requestMetadata) {
        AgentRuntimeProfile profile = request == null ? null : request.getAgentRuntimeProfile();
        GraphRuntimeContext runtimeContext = request == null ? null : request.getGraphRuntimeContext();
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
        metadata.putIfAbsent("runtimePlacement", runtimePlacementOf(request));
        if (runtimeContext != null) {
            metadata.putIfAbsent("sourceType", runtimeContext.getSourceType());
            metadata.putIfAbsent("sourceId", runtimeContext.getSourceId());
            if (runtimeContext.getExtra() != null) {
                Map<String, Object> extra = runtimeContext.getExtra();
                metadata.putIfAbsent("workflowId", extra.get("workflowId"));
                metadata.putIfAbsent("workflowKeySlug", extra.get("workflowKeySlug"));
                metadata.putIfAbsent("workflowVersion", extra.get("workflowVersion"));
                metadata.putIfAbsent("workflowVersionId", extra.get("workflowVersionId"));
                metadata.putIfAbsent("entryAgentId", extra.get("entryAgentId"));
                metadata.putIfAbsent("entryAgentKeySlug", extra.get("entryAgentKeySlug"));
            }
            if (runtimeContext.getSourceType() != null
                    && runtimeContext.getSourceType().toUpperCase().startsWith("WORKFLOW")) {
                metadata.putIfAbsent("workflowId", runtimeContext.getSourceId());
                metadata.putIfAbsent("workflowKeySlug", runtimeContext.getSourceKeySlug());
                metadata.putIfAbsent("workflowVersion", runtimeContext.getSourceVersion());
                metadata.putIfAbsent("workflowVersionId", runtimeContext.getSourceVersionId());
            }
        } else if (profile != null && profile.getExtra() != null) {
            metadata.putIfAbsent("version", profile.getExtra().get("__version"));
            metadata.putIfAbsent("versionId", profile.getExtra().get("__versionId"));
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

        if (!hasIntentRoute(raw)) {
            log.warn("[AgentRouter] Intent '{}' has no enabled Agent route, fallback to {}", raw, GENERAL_CHAT);
            return GENERAL_CHAT;
        }
        return raw;
    }

    private boolean hasIntentRoute(String intentType) {
        if (!StringUtils.hasText(intentType)) {
            return false;
        }
        if (!bindingService.listEnabledByIntentType(intentType).isEmpty()) {
            return true;
        }
        return agentEntryService.findByIntentType(intentType).isPresent();
    }

    private String runtimeTypeOf(AgentRuntimeRequest request) {
        if (request != null && request.getGraphRuntimeContext() != null
                && request.getGraphRuntimeContext().getRuntimeType() != null
                && !request.getGraphRuntimeContext().getRuntimeType().isBlank()) {
            return request.getGraphRuntimeContext().getRuntimeType();
        }
        AgentRuntimeProfile profile = request == null ? null : request.getAgentRuntimeProfile();
        if (profile != null && profile.getRuntimeType() != null && !profile.getRuntimeType().isBlank()) {
            return profile.getRuntimeType();
        }
        return AgentRuntimeAdapter.DEFAULT_RUNTIME_TYPE;
    }

    private String runtimePlacementOf(AgentRuntimeRequest request) {
        if (request != null && request.getGraphRuntimeContext() != null
                && request.getGraphRuntimeContext().getRuntimePlacement() != null
                && !request.getGraphRuntimeContext().getRuntimePlacement().isBlank()) {
            return request.getGraphRuntimeContext().getRuntimePlacement().trim().toUpperCase();
        }
        AgentRuntimeProfile profile = request == null ? null : request.getAgentRuntimeProfile();
        if (profile != null && profile.getRuntimePlacement() != null && !profile.getRuntimePlacement().isBlank()) {
            return profile.getRuntimePlacement().trim().toUpperCase();
        }
        return CENTRAL;
    }

    private EmbeddedTarget embeddedTarget(GraphRuntimeContext runtimeContext, AgentRuntimeProfile profile) {
        Map<String, Object> runtimeConfig = runtimeContext == null ? null : runtimeContext.getRuntimeConfig();
        if (runtimeConfig == null && profile != null) {
            runtimeConfig = profile.getRuntimeConfig();
        }
        if (runtimeConfig == null) {
            return null;
        }
        Object raw = runtimeConfig.get("embeddedRuntime");
        if (!(raw instanceof Map<?, ?>)) {
            raw = runtimeConfig.get("runtimeInstance");
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

    private String agentKeyOf(GraphRuntimeContext runtimeContext, AgentRuntimeProfile profile) {
        if (runtimeContext != null) {
            if (runtimeContext.getSourceKeySlug() != null && !runtimeContext.getSourceKeySlug().isBlank()) {
                return runtimeContext.getSourceKeySlug();
            }
            if (runtimeContext.getSourceId() != null && !runtimeContext.getSourceId().isBlank()) {
                return runtimeContext.getSourceId();
            }
            if (runtimeContext.getName() != null && !runtimeContext.getName().isBlank()) {
                return runtimeContext.getName();
            }
        }
        return agentKeyOf(profile);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String agentKeyOf(AgentRuntimeProfile profile) {
        if (profile == null) {
            return null;
        }
        if (profile.getKeySlug() != null && !profile.getKeySlug().isBlank()) {
            return profile.getKeySlug();
        }
        if (profile.getId() != null && !profile.getId().isBlank()) {
            return profile.getId();
        }
        return profile.getName();
    }

    private record EmbeddedTarget(String projectCode, String instanceId) {
    }
}
