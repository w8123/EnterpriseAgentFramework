package com.enterprise.ai.capability.catalog.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.capability.catalog.CapabilitySourceOwnership;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CapabilityToolCatalogService {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };
    public static final String SOURCE_CODE = "code";
    private static final String SOURCE_MANUAL = "manual";
    private static final String SOURCE_SCANNER = "scanner";

    private final ToolDefinitionMapper toolMapper;
    private final ScanProjectMapper projectMapper;
    private final ScanProjectToolMapper scanToolMapper;
    private final ObjectMapper objectMapper;

    public IPage<ToolDefinitionEntity> page(int current,
                                            int size,
                                            String keyword,
                                            String source,
                                            Boolean enabled,
                                            Long projectId) {
        int pageNum = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        LambdaQueryWrapper<ToolDefinitionEntity> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim();
            wrapper.and(q -> q.like(ToolDefinitionEntity::getName, term)
                    .or()
                    .like(ToolDefinitionEntity::getQualifiedName, term)
                    .or()
                    .like(ToolDefinitionEntity::getTitle, term)
                    .or()
                    .like(ToolDefinitionEntity::getDescription, term));
        }
        if (StringUtils.hasText(source)) {
            wrapper.eq(ToolDefinitionEntity::getSource, source.trim().toLowerCase(Locale.ROOT));
        }
        if (enabled != null) {
            wrapper.eq(ToolDefinitionEntity::getEnabled, enabled);
        }
        if (projectId != null) {
            wrapper.eq(ToolDefinitionEntity::getProjectId, projectId);
        }
        wrapper.orderByAsc(ToolDefinitionEntity::getTitle)
                .orderByAsc(ToolDefinitionEntity::getName);
        return toolMapper.selectPage(new Page<>(pageNum, pageSize, true), wrapper);
    }

    public Optional<ToolDefinitionEntity> findByName(String name) {
        return findByName(name, false);
    }

    private Optional<ToolDefinitionEntity> findByName(String name, boolean forUpdate) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name.trim())
                .last(forUpdate ? "limit 1 for update" : "limit 1")));
    }

    public ToolDefinitionEntity create(ToolDefinitionUpsertRequest request) {
        validateRequest(request, false);
        if (findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("tool already exists: " + request.name());
        }
        ToolDefinitionEntity entity = applyRequest(new ToolDefinitionEntity(), request, false);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        toolMapper.insert(entity);
        return entity;
    }

    @Transactional
    public ToolDefinitionEntity update(String name, ToolDefinitionUpsertRequest request) {
        validateRequest(request, true);
        ToolDefinitionEntity existing = findByName(name, true)
                .orElseThrow(() -> new IllegalArgumentException("tool does not exist: " + name));
        CapabilitySourceOwnership.requireCatalogWritable(existing);
        String storedSource = existing.getSource();
        ToolDefinitionEntity updated = applyRequest(existing, request, true);
        updated.setSource(storedSource);
        updated.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(updated);
        return updated;
    }

    @Transactional
    public boolean delete(String name) {
        ToolDefinitionEntity existing = findByName(name, true).orElse(null);
        if (existing == null) {
            return false;
        }
        CapabilitySourceOwnership.requireCatalogWritable(existing);
        if (SOURCE_CODE.equalsIgnoreCase(existing.getSource())) {
            throw new IllegalArgumentException("code-owned tool cannot be deleted");
        }
        return toolMapper.deleteById(existing.getId()) > 0;
    }

    @Transactional
    public ToolDefinitionEntity toggle(String name, boolean enabled) {
        ToolDefinitionEntity existing = findByName(name, true)
                .orElseThrow(() -> new IllegalArgumentException("tool does not exist: " + name));
        CapabilitySourceOwnership.requireCatalogWritable(existing);
        existing.setEnabled(enabled);
        existing.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(existing);
        return existing;
    }

    public List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        if (!StringUtils.hasText(parametersJson)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(parametersJson);
            if (root.isArray()) {
                return objectMapper.convertValue(root, PARAMETER_LIST_TYPE);
            }
            if (isJsonSchemaObject(root)) {
                return parseJsonSchemaProperties(root);
            }
            throw new IllegalArgumentException("tool parameters json must be an array or JSON Schema object");
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid tool parameters json", ex);
        }
    }

    private boolean isJsonSchemaObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode type = node.get("type");
        return node.has("properties") || (type != null && type.isTextual() && "object".equals(type.asText()));
    }

    private List<ToolDefinitionParameter> parseJsonSchemaProperties(JsonNode schema) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) {
            return List.of();
        }
        Set<String> requiredNames = jsonSchemaRequiredNames(schema.get("required"));
        List<ToolDefinitionParameter> parameters = new ArrayList<>();
        properties.properties().forEach(property -> {
            JsonNode propertySchema = property.getValue();
            parameters.add(new ToolDefinitionParameter(
                    property.getKey(),
                    jsonSchemaType(propertySchema),
                    firstJsonText(propertySchema, "description", "title"),
                    requiredNames.contains(property.getKey()),
                    firstJsonText(propertySchema, "location", "in"),
                    jsonSchemaChildren(propertySchema),
                    null
            ));
        });
        return List.copyOf(parameters);
    }

    private Set<String> jsonSchemaRequiredNames(JsonNode required) {
        if (required == null || !required.isArray()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        required.forEach(item -> {
            if (item.isTextual()) {
                names.add(item.asText());
            }
        });
        return names;
    }

    private List<ToolDefinitionParameter> jsonSchemaChildren(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return List.of();
        }
        JsonNode childSchema = "array".equals(jsonSchemaType(schema)) ? schema.get("items") : schema;
        return isJsonSchemaObject(childSchema) ? parseJsonSchemaProperties(childSchema) : List.of();
    }

    private String jsonSchemaType(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return "object";
        }
        JsonNode type = schema.get("type");
        if (type != null && type.isTextual() && StringUtils.hasText(type.asText())) {
            return type.asText();
        }
        if (type != null && type.isArray()) {
            for (JsonNode candidate : type) {
                if (candidate.isTextual() && !"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        if (schema.has("properties")) {
            return "object";
        }
        if (schema.has("items")) {
            return "array";
        }
        return "object";
    }

    private String firstJsonText(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText();
            }
        }
        return null;
    }

    public String getProjectNameOrNull(Long projectId) {
        if (projectId == null) {
            return null;
        }
        ScanProjectEntity project = projectMapper.selectById(projectId);
        return project == null ? null : project.getName();
    }

    public Optional<ScanProjectToolEntity> findCatalogScanTool(ToolDefinitionEntity entity) {
        if (entity == null || entity.getProjectId() == null || entity.getId() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(scanToolMapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, entity.getProjectId())
                .eq(ScanProjectToolEntity::getGlobalToolDefinitionId, entity.getId())
                .last("limit 1")));
    }

    public boolean isSdkBackedTool(ToolDefinitionEntity entity) {
        return entity != null
                && (StringUtils.hasText(entity.getSourceQualifiedName())
                || CapabilitySourceOwnership.isSdkLocation(entity.getSourceLocation()));
    }

    private ToolDefinitionEntity applyRequest(ToolDefinitionEntity entity,
                                              ToolDefinitionUpsertRequest request,
                                              boolean updating) {
        CapabilitySourceOwnership.requireUnmanagedLocation(request.sourceLocation());
        if (!updating) {
            entity.setName(request.name().trim());
        }
        entity.setTitle(request.title().trim());
        entity.setDescription(request.description());
        entity.setParametersJson(writeJson(request.parameters() == null ? List.of() : request.parameters()));
        entity.setCapabilityMetadataJson(writeJson(request.capabilityMetadata()));
        entity.setProjectId(updating && request.projectId() == null ? entity.getProjectId() : request.projectId());
        entity.setProjectCode(updating && !StringUtils.hasText(request.projectCode())
                ? entity.getProjectCode()
                : trimToNull(request.projectCode()));
        String qualifiedName = resolveQualifiedName(request.qualifiedName(), entity.getProjectCode(), entity.getName());
        entity.setQualifiedName(updating && qualifiedName == null ? entity.getQualifiedName() : qualifiedName);
        entity.setSideEffect(normalizeSideEffect(request.sideEffect()));
        entity.setEnabled(request.enabled());
        entity.setSource(updating ? entity.getSource() : normalizeSource(request.source()));
        entity.setSourceLocation(request.sourceLocation());
        entity.setHttpMethod(request.httpMethod());
        entity.setBaseUrl(request.baseUrl());
        entity.setContextPath(request.contextPath());
        entity.setEndpointPath(request.endpointPath());
        entity.setRequestBodyType(request.requestBodyType());
        entity.setResponseType(request.responseType());
        return entity;
    }

    private void validateRequest(ToolDefinitionUpsertRequest request, boolean updating) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (!updating && !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("tool name is required");
        }
        if (!StringUtils.hasText(request.title())) {
            throw new IllegalArgumentException("tool title is required");
        }
        if (!StringUtils.hasText(request.description())) {
            throw new IllegalArgumentException("tool description is required");
        }
        String source = normalizeSource(request.source());
        if (!updating && SOURCE_CODE.equals(source)) {
            throw new IllegalArgumentException("code-owned tool cannot be manually created");
        }
        if ((SOURCE_MANUAL.equals(source) || SOURCE_SCANNER.equals(source)) && !StringUtils.hasText(request.httpMethod())) {
            throw new IllegalArgumentException("httpMethod is required");
        }
    }

    private String normalizeSource(String source) {
        return StringUtils.hasText(source) ? source.trim().toLowerCase(Locale.ROOT) : SOURCE_MANUAL;
    }

    private String normalizeSideEffect(String sideEffect) {
        return StringUtils.hasText(sideEffect) ? sideEffect.trim().toUpperCase(Locale.ROOT) : "WRITE";
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
            throw new IllegalArgumentException("invalid tool metadata", ex);
        }
    }
}
