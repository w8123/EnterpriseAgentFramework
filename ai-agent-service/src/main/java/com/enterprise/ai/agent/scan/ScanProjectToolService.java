package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpAiTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扫描项目内接口的持久化与「注册为全局 Tool」。
 */
@Service
public class ScanProjectToolService {

    private static final String SOURCE_SCANNER = "scanner";
    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };

    private final ScanProjectToolMapper mapper;
    private final ToolDefinitionService toolDefinitionService;
    private final SemanticDocService semanticDocService;
    private final ObjectMapper objectMapper;

    public ScanProjectToolService(ScanProjectToolMapper mapper,
                                  ToolDefinitionService toolDefinitionService,
                                  SemanticDocService semanticDocService,
                                  ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.toolDefinitionService = toolDefinitionService;
        this.semanticDocService = semanticDocService;
        this.objectMapper = objectMapper;
    }

    public List<ScanProjectToolEntity> listByProject(Long projectId) {
        return mapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .orderByAsc(ScanProjectToolEntity::getName));
    }

    public void deleteByProject(Long projectId) {
        mapper.delete(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId));
    }

    public Optional<ScanProjectToolEntity> findByProjectAndId(Long projectId, Long id) {
        ScanProjectToolEntity e = mapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(ScanProjectToolEntity::getId, id)
                .last("limit 1"));
        return Optional.ofNullable(e);
    }

    public boolean existsByProjectAndName(Long projectId, String name) {
        return mapper.selectCount(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(ScanProjectToolEntity::getName, name)) > 0;
    }

    @Transactional
    public ScanProjectToolEntity insertScanned(Long projectId, ToolDefinitionUpsertRequest request) {
        if (existsByProjectAndName(projectId, request.name())) {
            throw new IllegalArgumentException("项目内工具名已存在: " + request.name());
        }
        ScanProjectToolEntity e = new ScanProjectToolEntity();
        e.setProjectId(projectId);
        applyUpsert(e, request, true);
        mapper.insert(e);
        return e;
    }

    @Transactional
    public ScanProjectToolEntity update(Long projectId, Long id, ToolDefinitionUpsertRequest request) {
        ScanProjectToolEntity existing = findByProjectAndId(projectId, id)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + id));
        if (StringUtils.hasText(request.name()) && !request.name().equals(existing.getName())) {
            if (existsByProjectAndName(projectId, request.name())) {
                throw new IllegalArgumentException("项目内工具名已存在: " + request.name());
            }
            existing.setName(request.name().trim());
        }
        ToolDefinitionUpsertRequest merged = mergeRequestWithExistingChildren(request, existing);
        applyUpsert(existing, merged, false);
        mapper.updateById(existing);
        return existing;
    }

    /**
     * 编辑扫描接口时，前端只编辑顶层参数，body 子字段（children）不在编辑表中。按 name+location 把已有 children 合并回
     * 新请求，避免保存操作误删解析出来的 body 结构。请求端已显式传 children 的则保留请求值。
     */
    private ToolDefinitionUpsertRequest mergeRequestWithExistingChildren(ToolDefinitionUpsertRequest request,
                                                                        ScanProjectToolEntity existing) {
        if (request == null || request.parameters() == null || request.parameters().isEmpty()) {
            return request;
        }
        List<ToolDefinitionParameter> previous = parseParameters(existing.getParametersJson());
        if (previous.isEmpty()) {
            return request;
        }
        Map<String, ToolDefinitionParameter> previousIndex = new java.util.HashMap<>();
        for (ToolDefinitionParameter p : previous) {
            previousIndex.put(childrenMergeKey(p.name(), p.location()), p);
        }
        List<ToolDefinitionParameter> mergedParams = request.parameters().stream()
                .map(incoming -> {
                    if (incoming.children() != null && !incoming.children().isEmpty()) {
                        return incoming;
                    }
                    ToolDefinitionParameter old = previousIndex.get(childrenMergeKey(incoming.name(), incoming.location()));
                    if (old == null || old.children() == null || old.children().isEmpty()) {
                        return incoming;
                    }
                    return new ToolDefinitionParameter(
                            incoming.name(),
                            incoming.type(),
                            incoming.description(),
                            incoming.required(),
                            incoming.location(),
                            old.children()
                    );
                })
                .toList();
        return new ToolDefinitionUpsertRequest(
                request.name(),
                request.description(),
                mergedParams,
                request.source(),
                request.sourceLocation(),
                request.httpMethod(),
                request.baseUrl(),
                request.contextPath(),
                request.endpointPath(),
                request.requestBodyType(),
                request.responseType(),
                request.projectId(),
                request.enabled(),
                request.agentVisible(),
                request.lightweightEnabled()
        );
    }

    private static String childrenMergeKey(String name, String location) {
        return (location == null ? "" : location) + ":" + (name == null ? "" : name);
    }

    @Transactional
    public ScanProjectToolEntity toggle(Long projectId, Long id, boolean enabled) {
        ScanProjectToolEntity existing = findByProjectAndId(projectId, id)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + id));
        existing.setEnabled(enabled);
        mapper.updateById(existing);
        return existing;
    }

    public Object execute(Long projectId, Long id, Map<String, Object> args) {
        ScanProjectToolEntity st = findByProjectAndId(projectId, id)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + id));
        ToolDefinitionEntity proxy = ScanProjectToolAdapter.toDefinitionEntity(st);
        return new DynamicHttpAiTool(proxy, objectMapper).execute(args == null ? Map.of() : args);
    }

    /**
     * 写入全局 {@code tool_definition}，迁移语义文档引用，并删除扫描行。
     */
    /**
     * 将某扫描模块下（或 {@code moduleId} 为 null 表示未关联模块）全部接口注册为全局 Tool，顺序与列表一致。
     */
    @Transactional
    public List<ToolDefinitionEntity> promoteModuleToGlobalTools(Long projectId, Long moduleId) {
        LambdaQueryWrapper<ScanProjectToolEntity> w = new LambdaQueryWrapper<ScanProjectToolEntity>()
                .select(ScanProjectToolEntity::getId)
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .orderByAsc(ScanProjectToolEntity::getName);
        if (moduleId == null) {
            w.isNull(ScanProjectToolEntity::getModuleId);
        } else {
            w.eq(ScanProjectToolEntity::getModuleId, moduleId);
        }
        List<Long> ids = mapper.selectList(w).stream().map(ScanProjectToolEntity::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<ToolDefinitionEntity> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            out.add(promoteToGlobalTool(projectId, id));
        }
        return out;
    }

    @Transactional
    public ToolDefinitionEntity promoteToGlobalTool(Long projectId, Long scanToolId) {
        ScanProjectToolEntity st = findByProjectAndId(projectId, scanToolId)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + scanToolId));
        String globalName = allocateUniqueGlobalName(st.getName());
        List<ToolDefinitionParameter> parameters = parseParameters(st.getParametersJson());
        ToolDefinitionUpsertRequest req = new ToolDefinitionUpsertRequest(
                globalName,
                st.getDescription(),
                parameters,
                SOURCE_SCANNER,
                st.getSourceLocation(),
                st.getHttpMethod(),
                st.getBaseUrl(),
                st.getContextPath(),
                st.getEndpointPath(),
                st.getRequestBodyType(),
                st.getResponseType(),
                projectId,
                Boolean.TRUE.equals(st.getEnabled()),
                Boolean.TRUE.equals(st.getAgentVisible()),
                Boolean.TRUE.equals(st.getLightweightEnabled())
        );
        ToolDefinitionEntity created = toolDefinitionService.create(req);
        semanticDocService.migrateScanToolDocsToGlobal(projectId, scanToolId, created.getId());
        mapper.deleteById(st.getId());
        return created;
    }

    public void updateAiDescription(Long id, String summary) {
        ScanProjectToolEntity e = mapper.selectById(id);
        if (e == null) {
            return;
        }
        e.setAiDescription(summary);
        mapper.updateById(e);
    }

    private String allocateUniqueGlobalName(String base) {
        String candidate = base == null ? "tool" : base.trim();
        if (!StringUtils.hasText(candidate)) {
            candidate = "tool";
        }
        String c = candidate;
        int i = 2;
        while (toolDefinitionService.findByName(c).isPresent()) {
            c = candidate + "_" + i++;
        }
        return c;
    }

    private void applyUpsert(ScanProjectToolEntity e, ToolDefinitionUpsertRequest r, boolean inserting) {
        if (inserting) {
            e.setName(r.name().trim());
        }
        e.setDescription(r.description());
        e.setParametersJson(serializeParameters(r.parameters()));
        e.setSource(StringUtils.hasText(r.source()) ? r.source().trim() : SOURCE_SCANNER);
        e.setSourceLocation(r.sourceLocation());
        e.setHttpMethod(r.httpMethod());
        e.setBaseUrl(r.baseUrl());
        e.setContextPath(r.contextPath());
        e.setEndpointPath(r.endpointPath());
        e.setRequestBodyType(r.requestBodyType());
        e.setResponseType(r.responseType());
        e.setEnabled(r.enabled());
        e.setAgentVisible(r.agentVisible());
        e.setLightweightEnabled(r.lightweightEnabled());
    }

    private List<ToolDefinitionParameter> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, PARAMETER_LIST_TYPE);
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法解析工具参数 JSON", ex);
        }
    }

    private String serializeParameters(List<ToolDefinitionParameter> parameters) {
        try {
            return objectMapper.writeValueAsString(parameters == null ? List.of() : parameters);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化工具参数", ex);
        }
    }
}
