package com.enterprise.ai.control.aiassist;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai-coding/projects/{projectId}")
@RequiredArgsConstructor
public class ControlAiCodingAgentController {

    private final ControlAiCodingAgentAuthoringService authoringService;

    @GetMapping("/agents")
    public Map<String, Object> listAgents(@PathVariable Long projectId) {
        return authoringService.listAgents(projectId);
    }

    @PostMapping("/agents")
    public Map<String, Object> createAgent(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, ?> request) {
        return authoringService.createAgent(projectId, request);
    }

    @GetMapping("/agents/{agentId}")
    public Map<String, Object> getAgent(
            @PathVariable Long projectId,
            @PathVariable String agentId) {
        return authoringService.getAgent(projectId, agentId);
    }

    @PutMapping("/agents/{agentId}")
    public Map<String, Object> updateAgent(
            @PathVariable Long projectId,
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, ?> request) {
        return authoringService.updateAgentIdentity(projectId, agentId, request);
    }

    @PutMapping("/agents/{agentId}/config/draft")
    public Map<String, Object> saveConfigDraft(
            @PathVariable Long projectId,
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, ?> request) {
        return authoringService.saveConfigDraft(projectId, agentId, request);
    }

    @PostMapping("/agents/{agentId}/config/publish")
    public Map<String, Object> publishConfig(
            @PathVariable Long projectId,
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, ?> request) {
        return authoringService.publishConfig(projectId, agentId, request);
    }

    @GetMapping("/agent-skills/bindable")
    public Map<String, Object> listBindableSkills(
            @PathVariable Long projectId,
            @RequestParam(required = false) String search) {
        return authoringService.listBindableSkills(projectId, search);
    }

    @PostMapping("/agents/{agentId}/skills/attach")
    public Map<String, Object> attachSkill(
            @PathVariable Long projectId,
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, ?> request) {
        return authoringService.attachSkill(projectId, agentId, request);
    }

    @PostMapping("/agents/{agentId}/skills/detach")
    public Map<String, Object> detachSkill(
            @PathVariable Long projectId,
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, ?> request) {
        return authoringService.detachSkill(projectId, agentId, request);
    }

    @ExceptionHandler(ControlAiCodingAgentException.class)
    public ResponseEntity<Map<String, Object>> handle(ControlAiCodingAgentException exception) {
        return ResponseEntity.status(exception.status()).body(exception.toBody());
    }
}
