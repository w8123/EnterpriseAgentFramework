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
import com.fasterxml.jackson.core.type.TypeReference;
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
public class CapabilityToolCatalogService {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };
    public static final String KIND_TOOL = "TOOL";
    public static final String KIND_SKILL = "SKILL";
    private static final String SOURCE_CODE = "code";
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
        wrapper.orderByAsc(ToolDefinitionEntity::getName);
        return toolMapper.selectPage(new Page<>(pageNum, pageSize, true), wrapper);
    }

    public Optional<ToolDefinitionEntity> findByName(String name) {
        if (!StringUtils.hasText(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolMapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name.trim())
                .last("limit 1")));
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

    public ToolDefinitionEntity update(String name, ToolDefinitionUpsertRequest request) {
        validateRequest(request, true);
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("tool does not exist: " + name));
        String storedSource = existing.getSource();
        ToolDefinitionEntity updated = applyRequest(existing, request, true);
        updated.setSource(storedSource);
        updated.setUpdateTime(LocalDateTime.now());
        toolMapper.updateById(updated);
        return updated;
    }

    public boolean delete(String name) {
        ToolDefinitionEntity existing = findByName(name).orElse(null);
        if (existing == null) {
            return false;
        }
        if (SOURCE_CODE.equalsIgnoreCase(existing.getSource())) {
            throw new IllegalArgumentException("code-owned tool cannot be deleted");
        }
        return toolMapper.deleteById(existing.getId()) > 0;
    }

    public ToolDefinitionEntity toggle(String name, boolean enabled) {
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("tool does not exist: " + name));
        if (Boolean.TRUE.equals(existing.getDraft()) && enabled) {
            throw new IllegalArgumentException("draft tool cannot be enabled");
        }
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
            return objectMapper.readValue(parametersJson, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid tool parameters json", ex);
        }
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
                && StringUtils.hasText(entity.getProjectCode())
                && StringUtils.hasText(entity.getSourceLocation())
                && entity.getSourceLocation().startsWith("sdk:" + entity.getProjectCode() + ":");
    }

    private ToolDefinitionEntity applyRequest(ToolDefinitionEntity entity,
                                              ToolDefinitionUpsertRequest request,
                                              boolean updating) {
        String kind = normalizeKind(request.kind());
        if (!updating) {
            entity.setName(request.name().trim());
        }
        entity.setKind(kind);
        entity.setDescription(request.description());
        entity.setParametersJson(writeJson(request.parameters() == null ? List.of() : request.parameters()));
        entity.setCapabilityMetadataJson(writeJson(request.capabilityMetadata()));
        entity.setProjectId(updating && request.projectId() == null ? entity.getProjectId() : request.projectId());
        entity.setProjectCode(updating && !StringUtils.hasText(request.projectCode())
                ? entity.getProjectCode()
                : trimToNull(request.projectCode()));
        entity.setVisibility(updating && !StringUtils.hasText(request.visibility())
                ? defaultString(entity.getVisibility(), "PRIVATE")
                : defaultString(request.visibility(), "PRIVATE"));
        String qualifiedName = resolveQualifiedName(request.qualifiedName(), entity.getProjectCode(), entity.getName());
        entity.setQualifiedName(updating && qualifiedName == null ? entity.getQualifiedName() : qualifiedName);
        entity.setAgentVisible(request.agentVisible());
        entity.setLightweightEnabled(request.lightweightEnabled());
        entity.setSideEffect(normalizeSideEffect(request.sideEffect()));
        entity.setDraft(false);
        entity.setEnabled(request.enabled());
        entity.setSkillKind(null);
        entity.setSpecJson(null);
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
        if (!KIND_TOOL.equals(normalizeKind(request.kind()))) {
            throw new IllegalArgumentException("tool catalog endpoint only accepts TOOL kind");
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

    private String normalizeKind(String kind) {
        if (!StringUtils.hasText(kind)) {
            return KIND_TOOL;
        }
        return kind.trim().toUpperCase(Locale.ROOT);
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
            throw new IllegalArgumentException("invalid tool metadata", ex);
        }
    }
}
