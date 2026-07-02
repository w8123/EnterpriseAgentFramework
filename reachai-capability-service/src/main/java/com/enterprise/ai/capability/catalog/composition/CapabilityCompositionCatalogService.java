package com.enterprise.ai.capability.catalog.composition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CapabilityCompositionCatalogService {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };
    public static final String KIND_SKILL = "SKILL";
    public static final String KIND_TOOL = "TOOL";
    public static final String SKILL_KIND_SUB_AGENT = "SUB_AGENT";
    public static final String SKILL_KIND_INTERACTIVE_FORM = "INTERACTIVE_FORM";
    private static final String SOURCE_CODE = "code";
    private static final String SOURCE_MANUAL = "manual";

    private final ToolDefinitionMapper mapper;
    private final ObjectMapper objectMapper;

    public IPage<ToolDefinitionEntity> page(int current,
                                            int size,
                                            String keyword,
                                            Boolean enabled,
                                            Boolean draft,
                                            Long projectId) {
        int pageNum = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        LambdaQueryWrapper<ToolDefinitionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ToolDefinitionEntity::getKind, KIND_SKILL);
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim();
            wrapper.and(q -> q.like(ToolDefinitionEntity::getName, term)
                    .or()
                    .like(ToolDefinitionEntity::getDescription, term));
        }
        if (enabled != null) {
            wrapper.eq(ToolDefinitionEntity::getEnabled, enabled);
        }
        if (draft != null) {
            wrapper.eq(ToolDefinitionEntity::getDraft, draft);
        }
        if (projectId != null) {
            wrapper.eq(ToolDefinitionEntity::getProjectId, projectId);
        }
        wrapper.orderByAsc(ToolDefinitionEntity::getName);
        return mapper.selectPage(new Page<>(pageNum, pageSize, true), wrapper);
    }

    public Optional<ToolDefinitionEntity> findSkillByName(String name) {
        return findByName(name)
                .filter(entity -> KIND_SKILL.equalsIgnoreCase(entity.getKind()));
    }

    public ToolDefinitionEntity create(ToolDefinitionUpsertRequest request) {
        validateRequest(request, false);
        if (findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("composition already exists: " + request.name());
        }
        ToolDefinitionEntity entity = applyRequest(new ToolDefinitionEntity(), request, false);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        mapper.insert(entity);
        return entity;
    }

    public ToolDefinitionEntity update(String name, ToolDefinitionUpsertRequest request) {
        validateRequest(request, true);
        ToolDefinitionEntity existing = findSkillByName(name)
                .orElseThrow(() -> new IllegalArgumentException("composition does not exist: " + name));
        String storedSource = existing.getSource();
        ToolDefinitionEntity updated = applyRequest(existing, request, true);
        updated.setSource(storedSource);
        updated.setUpdateTime(LocalDateTime.now());
        mapper.updateById(updated);
        return updated;
    }

    public boolean delete(String name) {
        ToolDefinitionEntity existing = findSkillByName(name).orElse(null);
        if (existing == null) {
            return false;
        }
        if (SOURCE_CODE.equalsIgnoreCase(existing.getSource())) {
            throw new IllegalArgumentException("code-owned composition cannot be deleted");
        }
        return mapper.deleteById(existing.getId()) > 0;
    }

    public ToolDefinitionEntity toggle(String name, boolean enabled) {
        ToolDefinitionEntity existing = findSkillByName(name)
                .orElseThrow(() -> new IllegalArgumentException("composition does not exist: " + name));
        if (Boolean.TRUE.equals(existing.getDraft()) && enabled) {
            throw new IllegalArgumentException("draft composition cannot be enabled");
        }
        existing.setEnabled(enabled);
        existing.setUpdateTime(LocalDateTime.now());
        mapper.updateById(existing);
        return existing;
    }

    public List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        if (!StringUtils.hasText(parametersJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(parametersJson, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid composition parameters json", ex);
        }
    }

    public Object parseSpecForDto(ToolDefinitionEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getSpecJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(entity.getSpecJson(), Object.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private Optional<ToolDefinitionEntity> findByName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name.trim())
                .last("limit 1")));
    }

    private ToolDefinitionEntity applyRequest(ToolDefinitionEntity entity,
                                              ToolDefinitionUpsertRequest request,
                                              boolean updating) {
        if (!updating) {
            entity.setName(request.name().trim());
        }
        boolean draft = Boolean.TRUE.equals(request.draft());
        entity.setKind(KIND_SKILL);
        entity.setDescription(resolveDescription(request.description(), draft));
        entity.setParametersJson(writeJson(request.parameters() == null ? List.of() : request.parameters()));
        entity.setCapabilityMetadataJson(writeJson(request.capabilityMetadata()));
        entity.setSource(updating ? entity.getSource() : normalizeSource(request.source()));
        entity.setSourceLocation(request.sourceLocation());
        entity.setProjectId(updating && request.projectId() == null ? entity.getProjectId() : request.projectId());
        entity.setProjectCode(updating && !StringUtils.hasText(request.projectCode())
                ? entity.getProjectCode()
                : trimToNull(request.projectCode()));
        entity.setVisibility(updating && !StringUtils.hasText(request.visibility())
                ? defaultString(entity.getVisibility(), "PRIVATE")
                : defaultString(request.visibility(), "PRIVATE"));
        String qualifiedName = resolveQualifiedName(request.qualifiedName(), entity.getProjectCode(), entity.getName());
        entity.setQualifiedName(updating && qualifiedName == null ? entity.getQualifiedName() : qualifiedName);
        entity.setEnabled(draft ? false : request.enabled());
        entity.setAgentVisible(request.agentVisible());
        entity.setLightweightEnabled(false);
        entity.setSideEffect(normalizeSideEffect(request.sideEffect()));
        entity.setDraft(draft);
        entity.setSkillKind(normalizeSkillKind(request.skillKind()));
        entity.setSpecJson(request.specJson());
        entity.setHttpMethod(null);
        entity.setBaseUrl(null);
        entity.setContextPath(null);
        entity.setEndpointPath(null);
        entity.setRequestBodyType(null);
        entity.setResponseType(null);
        return entity;
    }

    private void validateRequest(ToolDefinitionUpsertRequest request, boolean updating) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (!updating && !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("composition name is required");
        }
        if (!KIND_SKILL.equalsIgnoreCase(defaultString(request.kind(), KIND_SKILL))) {
            throw new IllegalArgumentException("composition endpoint only accepts SKILL kind");
        }
        if (Boolean.TRUE.equals(request.draft())) {
            return;
        }
        if (!StringUtils.hasText(request.description())) {
            throw new IllegalArgumentException("composition description is required");
        }
        if (!StringUtils.hasText(request.specJson())) {
            throw new IllegalArgumentException("composition specJson is required");
        }
        JsonNode spec = readSpec(request.specJson());
        String skillKind = normalizeSkillKind(request.skillKind());
        if (SKILL_KIND_INTERACTIVE_FORM.equals(skillKind)) {
            validateInteractiveFormSpec(request.name(), spec);
        } else {
            validateSubAgentSpec(request.name(), spec);
        }
    }

    private void validateSubAgentSpec(String name, JsonNode spec) {
        if (!hasText(spec, "systemPrompt")) {
            throw new IllegalArgumentException("SubAgentSkill[" + name + "] requires systemPrompt");
        }
        JsonNode whitelist = spec.get("toolWhitelist");
        if (whitelist == null || !whitelist.isArray() || whitelist.isEmpty()) {
            throw new IllegalArgumentException("SubAgentSkill[" + name + "] requires at least one toolWhitelist item");
        }
    }

    private void validateInteractiveFormSpec(String name, JsonNode spec) {
        if (!hasText(spec, "targetTool")) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + name + "] requires targetTool");
        }
        String targetTool = spec.get("targetTool").asText();
        ToolDefinitionEntity target = findByName(targetTool).orElse(null);
        if (target == null || !KIND_TOOL.equalsIgnoreCase(target.getKind())) {
            throw new IllegalArgumentException("targetTool must be TOOL: " + targetTool);
        }
        JsonNode fields = spec.get("fields");
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            throw new IllegalArgumentException("InteractiveFormSkill[" + name + "] requires fields");
        }
        validateFieldTree(name, fields);
    }

    private void validateFieldTree(String name, JsonNode fields) {
        for (JsonNode field : fields) {
            if (!hasText(field, "key")) {
                throw new IllegalArgumentException("InteractiveFormSkill[" + name + "] field.key is required");
            }
            if (!hasText(field, "label")) {
                throw new IllegalArgumentException("InteractiveFormSkill[" + name + "] field.label is required");
            }
            JsonNode children = field.get("children");
            if (children != null && children.isArray() && !children.isEmpty()) {
                validateFieldTree(name, children);
            } else {
                JsonNode source = field.get("source");
                if (source == null || !hasText(source, "kind")) {
                    throw new IllegalArgumentException("InteractiveFormSkill[" + name + "] field.source.kind is required");
                }
            }
        }
    }

    private JsonNode readSpec(String specJson) {
        try {
            return objectMapper.readTree(specJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid composition specJson", ex);
        }
    }

    private boolean hasText(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value != null && value.isTextual() && StringUtils.hasText(value.asText());
    }

    private String resolveDescription(String description, boolean draft) {
        if (StringUtils.hasText(description)) {
            return description;
        }
        return draft ? "(draft)" : description;
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.trim().toLowerCase(Locale.ROOT) : SOURCE_MANUAL;
    }

    private String normalizeSideEffect(String sideEffect) {
        return StringUtils.hasText(sideEffect) ? sideEffect.trim().toUpperCase(Locale.ROOT) : "WRITE";
    }

    private String normalizeSkillKind(String skillKind) {
        return StringUtils.hasText(skillKind) ? skillKind.trim().toUpperCase(Locale.ROOT) : SKILL_KIND_SUB_AGENT;
    }

    private String resolveQualifiedName(String requested, String projectCode, String name) {
        if (StringUtils.hasText(requested)) {
            return requested.trim();
        }
        if (StringUtils.hasText(projectCode) && StringUtils.hasText(name)) {
            return projectCode.trim() + ":" + name.trim();
        }
        return null;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid composition metadata", ex);
        }
    }
}
