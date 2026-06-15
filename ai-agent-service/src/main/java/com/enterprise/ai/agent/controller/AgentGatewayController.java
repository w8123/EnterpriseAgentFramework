package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agentscope.AgentRouter;
import com.enterprise.ai.agent.model.AgentResult;
import com.enterprise.ai.agent.model.ChatRequest;
import com.enterprise.ai.agent.model.ChatResponse;
import com.enterprise.ai.agent.runtime.AgentRuntimeProfile;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormResumeService;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentGatewayController {

    private final AgentEntryService agentEntryService;
    private final AgentWorkflowResolver workflowResolver;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowRuntimeService workflowRuntimeService;
    private final ObjectMapper objectMapper;
    private final AgentRouter agentRouter;
    private final InteractiveFormResumeService interactiveFormResumeService;

    @PostMapping("/{key}/chat")
    public ResponseEntity<ChatResponse> chat(@PathVariable("key") String key,
                                             @Valid @RequestBody ChatRequest request) {
        log.info("[AgentGateway] 收到请求: key={}, userId={}", key, request.getUserId());

        AgentEntryEntity entry = agentEntryService.findByKeySlug(key)
                .or(() -> agentEntryService.findById(key))
                .orElse(null);
        if (entry == null || Boolean.FALSE.equals(entry.getEnabled())) {
            return ResponseEntity.badRequest().body(ChatResponse.error("Agent 不存在或已禁用: " + key));
        }

        String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                ? request.getSessionId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        boolean hasIx = request.getInteractionId() != null && !request.getInteractionId().isBlank();
        boolean hasMsg = request.getMessage() != null && !request.getMessage().isBlank();
        if (!hasIx && !hasMsg) {
            return ResponseEntity.badRequest().body(ChatResponse.error("请提供 message，或提供 interactionId 继续交互"));
        }

        AgentResult result;
        if (hasIx) {
            result = interactiveFormResumeService.resume(request, sessionId);
        } else {
            result = executeEntry(entry, sessionId, request);
        }

        AgentRuntimeProfile profile = AgentRuntimeProfile.fromAgentEntry(entry, objectMapper);
        Map<String, Object> metadata = result.getMetadata() == null ? new HashMap<>()
                : new HashMap<>(result.getMetadata());
        metadata.put("agentKey", key);

        ChatResponse response = ChatResponse.builder()
                .sessionId(sessionId)
                .answer(result.getAnswer())
                .intentType(profile.getIntentType())
                .toolCalls(toList(metadata.get("toolCalls")))
                .reasoningSteps(toList(metadata.get("steps")))
                .metadata(metadata)
                .uiRequest(result.getUiRequest())
                .build();
        return ResponseEntity.ok(response);
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

    @SuppressWarnings("unchecked")
    private List<String> toList(Object value) {
        if (value instanceof List<?>) {
            return (List<String>) value;
        }
        return null;
    }
}
