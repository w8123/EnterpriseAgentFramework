package com.enterprise.ai.agent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.skill.SubAgentSkillFactory;
import com.enterprise.ai.agent.skill.SubAgentSpec;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormSkillFactory;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormSpec;
import com.enterprise.ai.agent.tool.log.ToolCallLogService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Skill 管理 API — Phase 2.0 仅实现 {@code SUB_AGENT} 形态。
 * <p>
 * 与 {@link ToolController} 分离是为了让前端有独立的"粗粒度能力"入口，
 * 底层还是同一张 {@code tool_definition} 表（kind=SKILL）。
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private static final String PLACEHOLDER_SUB_AGENT_SPEC =
            "{\"systemPrompt\":\"\",\"toolWhitelist\":[],\"maxSteps\":8,\"useMultiAgentModel\":false}";
    private static final String PLACEHOLDER_INTERACTIVE_SPEC =
            "{\"targetTool\":\"\",\"fields\":[]}";

    private final ToolDefinitionService toolDefinitionService;
    private final SubAgentSkillFactory subAgentSkillFactory;
    private final InteractiveFormSkillFactory interactiveFormSkillFactory;
    private final ObjectMapper objectMapper;
    private final ToolCallLogService toolCallLogService;

    @GetMapping
    public ResponseEntity<SkillListPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean draft) {
        IPage<ToolDefinitionEntity> page = toolDefinitionService.pageSkills(current, size, keyword, enabled, draft);
        List<SkillInfoDTO> records = page.getRecords().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(new SkillListPageResponse(
                records,
                page.getTotal(),
                page.getSize(),
                page.getCurrent(),
                page.getPages()
        ));
    }

    @GetMapping("/{name}")
    public ResponseEntity<SkillInfoDTO> get(@PathVariable String name) {
        return toolDefinitionService.findByName(name)
                .filter(e -> ToolDefinitionService.KIND_SKILL.equalsIgnoreCase(e.getKind()))
                .map(entity -> ResponseEntity.ok(toDto(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SkillUpsertRequest request) {
        try {
            ToolDefinitionEntity created = toolDefinitionService.create(
                    request.toServiceRequest(objectMapper, subAgentSkillFactory, interactiveFormSkillFactory));
            return ResponseEntity.ok(toDto(created));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name,
                                    @RequestBody SkillUpsertRequest request) {
        try {
            ToolDefinitionEntity updated = toolDefinitionService.update(name,
                    request.toServiceRequest(objectMapper, subAgentSkillFactory, interactiveFormSkillFactory));
            return ResponseEntity.ok(toDto(updated));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("不存在")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        try {
            if (!toolDefinitionService.isSkill(name)) {
                return ResponseEntity.notFound().build();
            }
            boolean deleted = toolDefinitionService.delete(name);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(null);
        }
    }

    @PutMapping("/{name}/toggle")
    public ResponseEntity<?> toggle(@PathVariable String name,
                                    @RequestBody SkillToggleRequest request) {
        try {
            if (!toolDefinitionService.isSkill(name)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(toDto(toolDefinitionService.toggle(name, request.enabled())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{name}/test")
    public ResponseEntity<SkillTestResultDTO> test(@PathVariable String name,
                                                   @RequestBody SkillTestRequest request) {
        if (!toolDefinitionService.isSkill(name)) {
            return ResponseEntity.notFound().build();
        }
        long start = System.currentTimeMillis();
        try {
            Object result = toolDefinitionService.executeTool(
                    name, request.args() == null ? Map.of() : request.args());
            long duration = System.currentTimeMillis() - start;
            log.info("[SkillController] 测试 Skill {} 成功, 耗时 {}ms", name, duration);
            return ResponseEntity.ok(new SkillTestResultDTO(true, String.valueOf(result), null, duration));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("[SkillController] 测试 Skill {} 失败: {}", name, e.getMessage());
            return ResponseEntity.ok(new SkillTestResultDTO(false, null, e.getMessage(), duration));
        }
    }

    @GetMapping("/{name}/metrics")
    public ResponseEntity<?> metrics(@PathVariable String name,
                                     @RequestParam(defaultValue = "7") int days) {
        if (!toolDefinitionService.isSkill(name)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toolCallLogService.getSkillMetrics(name, days));
    }

    private SkillInfoDTO toDto(ToolDefinitionEntity entity) {
        List<SkillParameterDTO> params = toolDefinitionService.parseParameters(entity.getParametersJson()).stream()
                .map(SkillParameterDTO::from)
                .toList();
        Object spec = parseSpecForDto(entity);
        return new SkillInfoDTO(
                entity.getName(),
                entity.getDescription(),
                entity.getAiDescription(),
                params,
                entity.getSkillKind(),
                entity.getSideEffect(),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getAgentVisible()),
                entity.getSource(),
                spec,
                Boolean.TRUE.equals(entity.getDraft())
        );
    }

    private Object parseSpecForDto(ToolDefinitionEntity entity) {
        if (entity.getSpecJson() == null || entity.getSpecJson().isBlank()) {
            return null;
        }
        String sk = entity.getSkillKind();
        if (sk != null && InteractiveFormSkillFactory.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(sk.trim())) {
            try {
                return interactiveFormSkillFactory.parseSpec(entity.getSpecJson());
            } catch (Exception e1) {
                try {
                    return objectMapper.readValue(entity.getSpecJson(), Map.class);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        try {
            return subAgentSkillFactory.parseSpec(entity.getSpecJson());
        } catch (Exception ignored) {
            try {
                return objectMapper.readValue(entity.getSpecJson(), Map.class);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    record SkillListPageResponse(List<SkillInfoDTO> records,
                                 long total,
                                 long size,
                                 long current,
                                 long pages) {}

    record SkillInfoDTO(String name,
                        String description,
                        String aiDescription,
                        List<SkillParameterDTO> parameters,
                        String skillKind,
                        String sideEffect,
                        boolean enabled,
                        boolean agentVisible,
                        String source,
                        Object spec,
                        boolean draft) {}

    record SkillParameterDTO(String name,
                             String type,
                             String description,
                             boolean required,
                             String location,
                             @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                             List<SkillParameterDTO> children) {
        static SkillParameterDTO from(ToolDefinitionParameter p) {
            List<ToolDefinitionParameter> raw = p.children();
            List<SkillParameterDTO> mapped = raw == null || raw.isEmpty()
                    ? List.of()
                    : raw.stream().map(SkillParameterDTO::from).toList();
            return new SkillParameterDTO(p.name(), p.type(), p.description(), p.required(), p.location(), mapped);
        }
    }

    record SkillUpsertRequest(String name,
                              String description,
                              List<ToolDefinitionParameter> parameters,
                              String skillKind,
                              String sideEffect,
                              boolean enabled,
                              boolean agentVisible,
                              JsonNode spec,
                              Boolean draft) {
        ToolDefinitionUpsertRequest toServiceRequest(ObjectMapper om,
                                                     SubAgentSkillFactory subFactory,
                                                     InteractiveFormSkillFactory ifFactory) {
            try {
                boolean isDraft = Boolean.TRUE.equals(draft);
                String resolvedKind = skillKind == null || skillKind.isBlank()
                        ? SubAgentSkillFactory.SKILL_KIND_SUB_AGENT
                        : skillKind.trim().toUpperCase();
                String specJson = spec == null || spec.isNull() ? null : om.writeValueAsString(spec);
                if (isDraft && (specJson == null || specJson.isBlank())) {
                    specJson = InteractiveFormSkillFactory.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(resolvedKind)
                            ? PLACEHOLDER_INTERACTIVE_SPEC
                            : PLACEHOLDER_SUB_AGENT_SPEC;
                }
                if (!isDraft) {
                    if (specJson == null || specJson.isBlank()) {
                        throw new IllegalArgumentException("Skill 必须提供 spec");
                    }
                    if (InteractiveFormSkillFactory.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(resolvedKind)) {
                        InteractiveFormSpec parsed = ifFactory.parseSpec(specJson);
                        ifFactory.validateSpec(name, parsed);
                    } else {
                        SubAgentSpec parsed = subFactory.parseSpec(specJson);
                        subFactory.validateSpec(name, parsed);
                    }
                }
                return ToolDefinitionUpsertRequest.skill(
                        name,
                        description,
                        parameters == null ? List.of() : parameters,
                        "manual",
                        null,
                        enabled,
                        agentVisible,
                        sideEffect,
                        resolvedKind,
                        specJson,
                        isDraft
                );
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("spec JSON 非法: " + e.getMessage());
            }
        }
    }

    record SkillToggleRequest(boolean enabled) {}

    record SkillTestRequest(Map<String, Object> args) {}

    record SkillTestResultDTO(boolean success, String result, String errorMessage, long durationMs) {}
}
