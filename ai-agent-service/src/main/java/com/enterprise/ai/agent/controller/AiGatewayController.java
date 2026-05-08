package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.agent.AgentVersionService;
import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    private final AgentDefinitionService agentDefinitionService;
    private final AgentVersionService versionService;
    private final AgentRouter agentRouter;
    private final ToolDefinitionService toolDefinitionService;

    @GetMapping("/catalog")
    public ResponseEntity<GatewayCatalog> catalog(@RequestParam(required = false) Long projectId) {
        List<GatewayAgentItem> agents = agentDefinitionService.list(projectId).stream()
                .filter(AgentDefinition::isEnabled)
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
        AgentDefinition head = agentDefinitionService.findByKeySlug(key).orElse(null);
        if (head == null || !head.isEnabled()) {
            return ResponseEntity.badRequest().body(ChatResponse.error("Agent 不存在或已禁用: " + key));
        }
        AgentDefinition snapshot = versionService.resolveActiveSnapshot(head.getId(), request.getUserId());
        if (snapshot == null) {
            snapshot = head;
        }
        String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AgentResult result = agentRouter.executeByDefinition(snapshot, sessionId, request.getUserId(),
                request.getMessage(), request.getRoles());
        Map<String, Object> metadata = result.getMetadata() == null ? new HashMap<>() : new HashMap<>(result.getMetadata());
        metadata.put("gateway", "ai-gateway");
        metadata.put("agentKey", key);
        return ResponseEntity.ok(ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.getAnswer())
                .intentType(snapshot.getIntentType())
                .metadata(metadata)
                .uiRequest(result.getUiRequest())
                .build());
    }

    private boolean isCatalogVisible(String visibility) {
        return "PUBLIC".equalsIgnoreCase(visibility) || "SHARED".equalsIgnoreCase(visibility);
    }

    public record GatewayCatalog(List<GatewayAgentItem> agents, List<GatewayCapabilityItem> capabilities) {
    }

    public record GatewayAgentItem(String id, String keySlug, String name, String projectCode, String visibility) {
    }

    public record GatewayCapabilityItem(Long id, String kind, String name, String qualifiedName,
                                        String projectCode, String visibility, String sideEffect) {
    }
}
