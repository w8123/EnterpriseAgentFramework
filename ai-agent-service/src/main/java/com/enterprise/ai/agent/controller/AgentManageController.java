package com.enterprise.ai.agent.controller;

import com.enterprise.ai.agent.agent.AgentDefinition;
import com.enterprise.ai.agent.agent.AgentDefinitionService;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.runtime.AgentRuntimeCapability;
import com.enterprise.ai.agent.runtime.AgentRuntimeRequest;
import com.enterprise.ai.agent.runtime.AgentRuntimeSelector;
import com.enterprise.ai.agent.runtime.AgentRuntimeValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Agent 配置管理 API
 * <p>
 * 提供 Agent 定义的 CRUD 接口，用于动态管理智能体的配置。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/definitions")
@RequiredArgsConstructor
public class AgentManageController {

    private final AgentDefinitionService definitionService;
    private final AgentRuntimeSelector runtimeSelector;

    @GetMapping
    public ResponseEntity<List<AgentDefinition>> list(@RequestParam(required = false) Long projectId) {
        return ResponseEntity.ok(definitionService.list(projectId));
    }

    @GetMapping("/runtimes")
    public ResponseEntity<List<AgentRuntimeCapability>> runtimes() {
        return ResponseEntity.ok(runtimeSelector.capabilities());
    }

    @GetMapping("/graph-node-types")
    public ResponseEntity<List<AgentGraphNodeType.Descriptor>> graphNodeTypes() {
        return ResponseEntity.ok(AgentGraphNodeType.catalog());
    }

    @PostMapping("/runtime-validation")
    public ResponseEntity<AgentRuntimeValidationResult> validateRuntime(@RequestBody AgentDefinition definition) {
        return ResponseEntity.ok(validateDefinitionRuntime(definition));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AgentDefinition> get(@PathVariable String id) {
        return definitionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AgentDefinition> create(@RequestBody AgentDefinition definition) {
        requireValidRuntime(definition);
        AgentDefinition created = definitionService.create(definition);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AgentDefinition> update(@PathVariable String id,
                                                  @RequestBody AgentDefinition definition) {
        try {
            AgentDefinition current = definitionService.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Agent 定义不存在: " + id));
            AgentDefinition merged = mergeForValidation(current, definition);
            requireValidRuntime(merged);
            AgentDefinition updated = definitionService.update(id, definition);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        boolean deleted = definitionService.delete(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private AgentRuntimeValidationResult validateDefinitionRuntime(AgentDefinition definition) {
        return runtimeSelector.validate(AgentRuntimeRequest.builder()
                .agentDefinition(definition)
                .build());
    }

    private void requireValidRuntime(AgentDefinition definition) {
        AgentRuntimeValidationResult validation = validateDefinitionRuntime(definition);
        if (!validation.isValid()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.getMessage());
        }
    }

    private AgentDefinition mergeForValidation(AgentDefinition current, AgentDefinition update) {
        AgentDefinition merged = AgentDefinition.builder()
                .id(current.getId())
                .keySlug(current.getKeySlug())
                .name(current.getName())
                .description(current.getDescription())
                .agentMode(current.getAgentMode())
                .projectId(current.getProjectId())
                .projectCode(current.getProjectCode())
                .visibility(current.getVisibility())
                .intentType(current.getIntentType())
                .systemPrompt(current.getSystemPrompt())
                .tools(current.getTools())
                .toolRefs(current.getToolRefs())
                .skills(current.getSkills())
                .skillRefs(current.getSkillRefs())
                .modelInstanceId(current.getModelInstanceId())
                .runtimeType(current.getRuntimeType())
                .runtimePlacement(current.getRuntimePlacement())
                .runtimeConfig(current.getRuntimeConfig())
                .defaultResourceConfig(current.getDefaultResourceConfig())
                .graphSpec(current.getGraphSpec())
                .maxSteps(current.getMaxSteps())
                .enabled(current.isEnabled())
                .type(current.getType())
                .pipelineAgentIds(current.getPipelineAgentIds())
                .knowledgeBaseGroupId(current.getKnowledgeBaseGroupId())
                .promptTemplateId(current.getPromptTemplateId())
                .outputSchemaType(current.getOutputSchemaType())
                .triggerMode(current.getTriggerMode())
                .useMultiAgentModel(current.isUseMultiAgentModel())
                .extra(current.getExtra())
                .canvasJson(current.getCanvasJson())
                .allowIrreversible(current.isAllowIrreversible())
                .createdAt(current.getCreatedAt())
                .updatedAt(current.getUpdatedAt())
                .build();
        if (update.getProjectCode() != null) merged.setProjectCode(update.getProjectCode());
        if (update.getAgentMode() != null) merged.setAgentMode(update.getAgentMode());
        if (update.getModelInstanceId() != null) merged.setModelInstanceId(update.getModelInstanceId());
        if (update.getRuntimeType() != null) merged.setRuntimeType(update.getRuntimeType());
        if (update.getRuntimePlacement() != null) merged.setRuntimePlacement(update.getRuntimePlacement());
        if (update.getRuntimeConfig() != null) merged.setRuntimeConfig(update.getRuntimeConfig());
        if (update.getDefaultResourceConfig() != null) merged.setDefaultResourceConfig(update.getDefaultResourceConfig());
        if (update.getGraphSpec() != null) merged.setGraphSpec(update.getGraphSpec());
        if (update.getTools() != null) merged.setTools(update.getTools());
        if (update.getToolRefs() != null) merged.setToolRefs(update.getToolRefs());
        if (update.getSkills() != null) merged.setSkills(update.getSkills());
        if (update.getSkillRefs() != null) merged.setSkillRefs(update.getSkillRefs());
        if (update.getType() != null) merged.setType(update.getType());
        if (update.getPipelineAgentIds() != null) merged.setPipelineAgentIds(update.getPipelineAgentIds());
        if (update.getSystemPrompt() != null) merged.setSystemPrompt(update.getSystemPrompt());
        if (update.getIntentType() != null) merged.setIntentType(update.getIntentType());
        if (update.getMaxSteps() > 0) merged.setMaxSteps(update.getMaxSteps());
        if (update.getKnowledgeBaseGroupId() != null) merged.setKnowledgeBaseGroupId(update.getKnowledgeBaseGroupId());
        if (update.getPromptTemplateId() != null) merged.setPromptTemplateId(update.getPromptTemplateId());
        if (update.getOutputSchemaType() != null) merged.setOutputSchemaType(update.getOutputSchemaType());
        if (update.getTriggerMode() != null) merged.setTriggerMode(update.getTriggerMode());
        merged.setUseMultiAgentModel(update.isUseMultiAgentModel());
        merged.setAllowIrreversible(update.isAllowIrreversible());
        return merged;
    }
}
