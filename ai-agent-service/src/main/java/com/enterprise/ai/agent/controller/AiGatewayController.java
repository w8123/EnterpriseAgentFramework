package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.AgentEntryService;
import com.enterprise.ai.agent.workflow.AgentWorkflowResolveRequest;
import com.enterprise.ai.agent.workflow.AgentWorkflowBindingEntity;
import com.enterprise.ai.agent.workflow.AgentWorkflowResolver;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionService;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeRequest;
import com.enterprise.ai.agent.workflow.WorkflowRuntimeService;
import com.enterprise.ai.agent.workflow.WorkflowVersionEntity;
import com.enterprise.ai.agent.workflow.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/gateway")
@RequiredArgsConstructor
public class AiGatewayController {

    private final AgentEntryService agentEntryService;
    private final AgentWorkflowResolver workflowResolver;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final ObjectMapper objectMapper;
    private final AgentRouter agentRouter;
    private final ToolDefinitionService toolDefinitionService;

    @GetMapping("/catalog")
    public ResponseEntity<GatewayCatalog> catalog(@RequestParam(required = false) Long projectId) {
        List<GatewayAgentItem> agents = agentEntryService.list(projectId, null, null).stream()
                .filter(entry -> entry.getEnabled() == null || entry.getEnabled())
                .filter(agent -> isCatalogVisible(agent.getVisibility()))
                .map(agent -> new GatewayAgentItem(agent.getId(), agent.getKeySlug(), agent.getName(),
                        agent.getProjectCode(), agent.getVisibility()))
                .toList();
        List<GatewayCapabilityItem> capabilities = toolDefinitionService.list().stream()
                .filter(tool -> projectId == null || projectId.equals(tool.getProjectId()))
                .filter(tool -> Boolean.TRUE.equals(tool.getEnabled()))
                .filter(tool -> isCatalogVisible(tool.getVisibility()))
                .map(tool -> new GatewayCapabilityItem(tool.getId(), tool.getKind(), tool.getName(),
                        tool.getQualifiedName(), tool.getProjectCode(), tool.getVisibility(), tool.getSideEffect()))
                .toList();
        return ResponseEntity.ok(new GatewayCatalog(agents, capabilities));
    }

    @PostMapping("/agents/{key}/chat")
    public ResponseEntity<ChatResponse> chat(@PathVariable String key,
                                             @Valid @RequestBody ChatRequest request) {
        AgentEntryEntity entry = agentEntryService.findByKeySlug(key)
                .or(() -> agentEntryService.findById(key))
                .orElse(null);
        if (entry == null || Boolean.FALSE.equals(entry.getEnabled())) {
            return ResponseEntity.badRequest().body(ChatResponse.error("Agent 不存在或已禁用: " + key));
        }
        String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AgentResult result = executeEntry(entry, sessionId, request);
        Map<String, Object> metadata = result.getMetadata() == null ? new HashMap<>() : new HashMap<>(result.getMetadata());
        metadata.put("gateway", "ai-gateway");
        metadata.put("agentKey", key);
        AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
        return ResponseEntity.ok(ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.getAnswer())
                .intentType(profile.getIntentType())
                .metadata(metadata)
                .uiRequest(result.getUiRequest())
                .build());
    }

    private AgentResult executeEntry(AgentEntryEntity entry, String sessionId, ChatRequest request) {
        AgentWorkflowBindingEntity binding = workflowResolver.resolve(new AgentWorkflowResolveRequest(
                entry.getId(), entry.getProjectCode(), null, null, null, null)).orElse(null);
        if (binding != null && StringUtils.hasText(binding.getWorkflowId())) {
            WorkflowDefinitionEntity workflow = workflowDefinitionService.findById(binding.getWorkflowId())
                    .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + binding.getWorkflowId()));
            WorkflowVersionEntity version = workflowVersionService.resolveActive(workflow.getId());
            Map<String, Object> principal = new HashMap<>();
            principal.put("userId", request.getUserId());
            principal.put("roles", request.getRoles());
            return workflowRuntimeService.execute(WorkflowRuntimeRequest.builder()
                    .agent(entry)
                    .workflow(workflow)
                    .activeVersion(version)
                    .allowDraftFallback(true)
                    .sessionId(sessionId)
                    .message(request.getMessage())
                    .principal(principal)
                    .build());
        }
        AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
        return agentRouter.executeByProfile(profile, sessionId, request.getUserId(), request.getMessage(), request.getRoles());
    }

    private static boolean isCatalogVisible(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return true;
        }
        return !"PRIVATE".equalsIgnoreCase(visibility.trim());
    }

    public record GatewayCatalog(List<GatewayAgentItem> agents, List<GatewayCapabilityItem> capabilities) {
    }

    public record GatewayAgentItem(String id, String keySlug, String name, String projectCode, String visibility) {
    }

    public record GatewayCapabilityItem(Long id, String kind, String name, String qualifiedName,
                                        String projectCode, String visibility, String sideEffect) {
    }
}
