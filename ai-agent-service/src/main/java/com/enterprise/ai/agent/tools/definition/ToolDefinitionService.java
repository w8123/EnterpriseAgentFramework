package com.enterprise.ai.agent.tools.definition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpAiTool;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.enterprise.ai.skill.scanner.manifest.ToolManifest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class ToolDefinitionService {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };
    private static final String SOURCE_CODE = "code";
    private static final String SOURCE_SCANNER = "scanner";
    private static final String SOURCE_MANUAL = "manual";

    private final ToolDefinitionMapper mapper;
    private final ToolRegistry toolRegistry;
    private final List<AiTool> codeTools;
    private final ObjectMapper objectMapper;

    public ToolDefinitionService(ToolDefinitionMapper mapper,
                                 ToolRegistry toolRegistry,
                                 List<AiTool> codeTools,
                                 ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.toolRegistry = toolRegistry;
        this.codeTools = codeTools;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        syncCodeTools();
        loadEnabledConfigTools();
    }

    public void syncCodeTools() {
        Set<String> currentNames = new LinkedHashSet<>();
        for (AiTool tool : codeTools) {
            currentNames.add(tool.name());
            ToolDefinitionEntity existing = findByName(tool.name()).orElse(null);
            ToolDefinitionEntity entity = existing == null ? new ToolDefinitionEntity() : existing;
            entity.setName(tool.name());
            entity.setDescription(tool.description());
            entity.setParametersJson(serializeParameters(tool.parameters().stream()
                    .map(this::toStoredParameter)
                    .toList()));
            entity.setSource(SOURCE_CODE);
            entity.setSourceLocation(tool.getClass().getName());

            if (existing == null) {
                entity.setEnabled(true);
                entity.setAgentVisible(true);
                entity.setLightweightEnabled("search_knowledge".equals(tool.name()));
                mapper.insert(entity);
            } else {
                mapper.updateById(entity);
            }
        }

        for (ToolDefinitionEntity existing : listBySource(SOURCE_CODE)) {
            if (!currentNames.contains(existing.getName())) {
                existing.setEnabled(false);
                mapper.updateById(existing);
            }
        }
    }

    public List<ToolDefinitionEntity> list() {
        return mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .orderByAsc(ToolDefinitionEntity::getName));
    }

    public Optional<ToolDefinitionEntity> findByName(String name) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name)
                .last("limit 1")));
    }

    public ToolDefinitionEntity create(ToolDefinitionUpsertRequest request) {
        validateRequest(request, false);
        if (findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("工具已存在: " + request.name());
        }
        ToolDefinitionEntity entity = applyRequest(new ToolDefinitionEntity(), request, false);
        mapper.insert(entity);
        registerIfNeeded(entity);
        return entity;
    }

    public ToolDefinitionEntity update(String name, ToolDefinitionUpsertRequest request) {
        validateRequest(request, true);
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));

        if (SOURCE_CODE.equals(existing.getSource())) {
            existing.setEnabled(request.enabled());
            existing.setAgentVisible(request.agentVisible());
            existing.setLightweightEnabled(request.lightweightEnabled());
            mapper.updateById(existing);
            return existing;
        }

        ToolDefinitionEntity updated = applyRequest(existing, request, true);
        updated.setId(existing.getId());
        updated.setSource(existing.getSource());
        mapper.updateById(updated);
        registerIfNeeded(updated);
        return updated;
    }

    public boolean delete(String name) {
        ToolDefinitionEntity existing = findByName(name).orElse(null);
        if (existing == null) {
            return false;
        }
        if (SOURCE_CODE.equals(existing.getSource())) {
            throw new IllegalArgumentException("Code Tool 不可删除，只能禁用");
        }
        return mapper.deleteById(existing.getId()) > 0;
    }

    public ToolDefinitionEntity toggle(String name, boolean enabled) {
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
        existing.setEnabled(enabled);
        mapper.updateById(existing);
        if (enabled) {
            registerIfNeeded(existing);
        }
        return existing;
    }

    public ImportResult importManifest(String yaml) {
        ToolManifest manifest = ToolManifest.fromYaml(yaml);
        int imported = 0;
        List<String> toolNames = new java.util.ArrayList<>();

        for (var tool : manifest.tools()) {
            ToolDefinitionEntity existing = findByName(tool.name()).orElse(null);
            if (existing != null && SOURCE_CODE.equals(existing.getSource())) {
                throw new IllegalArgumentException("Manifest 中的工具名与代码工具冲突: " + tool.name());
            }

            ToolDefinitionUpsertRequest request = new ToolDefinitionUpsertRequest(
                    tool.name(),
                    tool.description(),
                    tool.parameters().stream()
                            .map(parameter -> new ToolDefinitionParameter(
                                    parameter.name(),
                                    parameter.type(),
                                    parameter.description(),
                                    parameter.required(),
                                    parameter.location() == null ? null : parameter.location().name()
                            ))
                            .toList(),
                    SOURCE_SCANNER,
                    tool.source() == null ? null : tool.source().location(),
                    tool.method(),
                    manifest.project().baseUrl(),
                    manifest.project().contextPath(),
                    tool.path(),
                    tool.requestBodyType(),
                    tool.responseType(),
                    existing == null || Boolean.TRUE.equals(existing.getEnabled()),
                    existing == null || Boolean.TRUE.equals(existing.getAgentVisible()),
                    existing != null && Boolean.TRUE.equals(existing.getLightweightEnabled())
            );

            if (existing == null) {
                create(request);
            } else {
                update(tool.name(), request);
            }
            imported++;
            toolNames.add(tool.name());
        }

        return new ImportResult(imported, List.copyOf(toolNames));
    }

    public Object executeTool(String name, Map<String, Object> args) {
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
        if (SOURCE_CODE.equals(existing.getSource())) {
            return toolRegistry.execute(name, args == null ? Map.of() : args);
        }
        return buildDynamicTool(existing).execute(args == null ? Map.of() : args);
    }

    public List<String> listLightweightEnabledToolNames() {
        return mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                        .orderByAsc(ToolDefinitionEntity::getName))
                .stream()
                .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                .filter(entity -> Boolean.TRUE.equals(entity.getLightweightEnabled()))
                .map(ToolDefinitionEntity::getName)
                .toList();
    }

    public boolean isLightweightCallable(String name) {
        return findByName(name)
                .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                .filter(entity -> Boolean.TRUE.equals(entity.getLightweightEnabled()))
                .isPresent();
    }

    public boolean isAgentCallable(String name) {
        return findByName(name)
                .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                .filter(entity -> Boolean.TRUE.equals(entity.getAgentVisible()))
                .isPresent();
    }

    public List<ToolDefinitionParameter> parseParameters(String parametersJson) {
        if (parametersJson == null || parametersJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(parametersJson, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法解析工具参数 JSON", ex);
        }
    }

    private ToolDefinitionParameter toStoredParameter(ToolParameter parameter) {
        return new ToolDefinitionParameter(
                parameter.name(),
                parameter.type(),
                parameter.description(),
                parameter.required(),
                null
        );
    }

    private ToolDefinitionEntity applyRequest(ToolDefinitionEntity entity, ToolDefinitionUpsertRequest request, boolean updating) {
        entity.setName(updating ? entity.getName() : request.name());
        entity.setDescription(request.description());
        entity.setParametersJson(serializeParameters(request.parameters()));
        entity.setSource(normalizeSource(request.source()));
        entity.setSourceLocation(request.sourceLocation());
        entity.setHttpMethod(request.httpMethod());
        entity.setBaseUrl(request.baseUrl());
        entity.setContextPath(request.contextPath());
        entity.setEndpointPath(request.endpointPath());
        entity.setRequestBodyType(request.requestBodyType());
        entity.setResponseType(request.responseType());
        entity.setEnabled(request.enabled());
        entity.setAgentVisible(request.agentVisible());
        entity.setLightweightEnabled(request.lightweightEnabled());
        return entity;
    }

    private void validateRequest(ToolDefinitionUpsertRequest request, boolean updating) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (!updating && isBlank(request.name())) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (isBlank(request.description())) {
            throw new IllegalArgumentException("工具描述不能为空");
        }
        String source = normalizeSource(request.source());
        if (!updating && SOURCE_CODE.equals(source)) {
            throw new IllegalArgumentException("不允许手工创建 code 类型工具");
        }
        if ((SOURCE_MANUAL.equals(source) || SOURCE_SCANNER.equals(source)) && isBlank(request.httpMethod())) {
            throw new IllegalArgumentException("HTTP 工具必须提供 httpMethod");
        }
    }

    private String normalizeSource(String source) {
        if (isBlank(source)) {
            return SOURCE_MANUAL;
        }
        String normalized = source.trim().toLowerCase();
        if (!Set.of(SOURCE_CODE, SOURCE_MANUAL, SOURCE_SCANNER).contains(normalized)) {
            throw new IllegalArgumentException("未知工具来源: " + source);
        }
        return normalized;
    }

    private void loadEnabledConfigTools() {
        for (ToolDefinitionEntity entity : mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getEnabled, true)
                .ne(ToolDefinitionEntity::getSource, SOURCE_CODE))) {
            registerIfNeeded(entity);
        }
    }

    private void registerIfNeeded(ToolDefinitionEntity entity) {
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            return;
        }
        if (SOURCE_CODE.equals(entity.getSource())) {
            return;
        }
        toolRegistry.register(buildDynamicTool(entity));
    }

    private DynamicHttpAiTool buildDynamicTool(ToolDefinitionEntity entity) {
        return new DynamicHttpAiTool(entity, objectMapper);
    }

    private List<ToolDefinitionEntity> listBySource(String source) {
        return mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getSource, source));
    }

    private String serializeParameters(List<ToolDefinitionParameter> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters == null ? List.of() : parameters);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化工具参数", ex);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ImportResult(int importedCount, List<String> toolNames) {
    }
}
