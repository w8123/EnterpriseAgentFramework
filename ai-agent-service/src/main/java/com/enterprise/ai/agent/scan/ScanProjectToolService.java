package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.semantic.SemanticMarkdownUtil;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final ScanProjectMapper projectMapper;
    private final ToolDefinitionService toolDefinitionService;
    private final SemanticDocService semanticDocService;
    private final ObjectMapper objectMapper;

    public ScanProjectToolService(ScanProjectToolMapper mapper,
                                  ScanProjectMapper projectMapper,
                                  ToolDefinitionService toolDefinitionService,
                                  SemanticDocService semanticDocService,
                                  ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
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
        ScanProjectEntity project = projectMapper.selectById(projectId);
        var extras = ScanProjectAuthSupport.invocationExtras(project);
        return new DynamicHttpAiTool(proxy, objectMapper, extras).execute(args == null ? Map.of() : args);
    }

    /**
     * 将某扫描模块下（或 {@code moduleId} 为 null 表示未关联模块）全部尚未「添加为 Tool」的接口注册为全局 Tool，顺序与列表一致。
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
        w.isNull(ScanProjectToolEntity::getGlobalToolDefinitionId);
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
        if (st.getGlobalToolDefinitionId() != null) {
            return toolDefinitionService.findById(st.getGlobalToolDefinitionId())
                    .orElseThrow(() -> new IllegalStateException("关联的全局 Tool 已不存在，请重新扫描后再次添加"));
        }
        String globalName = allocateUniqueGlobalName(st.getName());
        List<ToolDefinitionParameter> parameters = parseParameters(st.getParametersJson());
        String inferredSideEffect = SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath());
        ToolDefinitionUpsertRequest req = new ToolDefinitionUpsertRequest(
                globalName,
                "TOOL",
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
                Boolean.TRUE.equals(st.getLightweightEnabled()),
                inferredSideEffect,
                null,
                null
        );
        ToolDefinitionEntity created = toolDefinitionService.create(req);
        semanticDocService.migrateScanToolDocsToGlobal(projectId, scanToolId, created.getId());
        syncAiDescriptionToGlobalTool(st, created.getId());
        st.setGlobalToolDefinitionId(created.getId());
        mapper.updateById(st);
        return toolDefinitionService.findById(created.getId()).orElse(created);
    }

    /**
     * 将扫描侧 AI 理解（冗余字段 + 若空则从已迁移的接口语义 Markdown 抽取）同步到全局 {@code tool_definition}。
     */
    private void syncAiDescriptionToGlobalTool(ScanProjectToolEntity st, long globalToolId) {
        String ai = null;
        if (StringUtils.hasText(st.getAiDescription())) {
            ai = st.getAiDescription().trim();
        } else {
            ai = semanticDocService
                    .findByLevelAndToolId(SemanticDocEntity.LEVEL_TOOL, globalToolId)
                    .map(d -> SemanticMarkdownUtil.extractToolSummary(d.getContentMd()))
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .orElse(null);
        }
        if (StringUtils.hasText(ai)) {
            toolDefinitionService.setAiDescriptionAndReindex(globalToolId, ai);
        }
    }

    /**
     * 扫描行与已关联的 {@code tool_definition} 在可同步字段上是否不一致（需要「更新到 Tool」）。
     */
    public boolean isScanDivergedFromGlobal(ScanProjectToolEntity st, ToolDefinitionEntity g) {
        if (g == null) {
            return false;
        }
        return !scanContentMatchesGlobal(st, g);
    }

    private boolean scanContentMatchesGlobal(ScanProjectToolEntity st, ToolDefinitionEntity g) {
        String kind = g.getKind();
        if (kind == null || kind.isBlank()) {
            kind = ToolDefinitionService.KIND_TOOL;
        }
        if (!ToolDefinitionService.KIND_TOOL.equalsIgnoreCase(kind)) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getDescription()), trimOrNull(g.getDescription()))) {
            return false;
        }
        if (!Objects.equals(parseParameters(st.getParametersJson()), parseParameters(g.getParametersJson()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getSourceLocation()), trimOrNull(g.getSourceLocation()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getHttpMethod()), trimOrNull(g.getHttpMethod()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getBaseUrl()), trimOrNull(g.getBaseUrl()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getContextPath()), trimOrNull(g.getContextPath()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getEndpointPath()), trimOrNull(g.getEndpointPath()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getRequestBodyType()), trimOrNull(g.getRequestBodyType()))) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getResponseType()), trimOrNull(g.getResponseType()))) {
            return false;
        }
        if (Boolean.TRUE.equals(st.getEnabled()) != Boolean.TRUE.equals(g.getEnabled())) {
            return false;
        }
        if (Boolean.TRUE.equals(st.getAgentVisible()) != Boolean.TRUE.equals(g.getAgentVisible())) {
            return false;
        }
        if (Boolean.TRUE.equals(st.getLightweightEnabled()) != Boolean.TRUE.equals(g.getLightweightEnabled())) {
            return false;
        }
        if (!Objects.equals(st.getProjectId(), g.getProjectId())) {
            return false;
        }
        String inferred = SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath());
        String gSide = g.getSideEffect() == null || g.getSideEffect().isBlank()
                ? "WRITE"
                : g.getSideEffect().trim().toUpperCase(Locale.ROOT);
        String iSide = inferred == null || inferred.isBlank()
                ? "WRITE"
                : inferred.trim().toUpperCase(Locale.ROOT);
        if (!Objects.equals(iSide, gSide)) {
            return false;
        }
        if (!Objects.equals(trimOrNull(st.getAiDescription()), trimOrNull(g.getAiDescription()))) {
            return false;
        }
        return true;
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 删除全局 Tool 并解除扫描行关联（语义文档中 level=tool 且该 toolId 的条目共删）。
     */
    @Transactional
    public ScanProjectToolEntity unpromoteFromGlobal(Long projectId, Long scanToolId) {
        ScanProjectToolEntity st = findByProjectAndId(projectId, scanToolId)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + scanToolId));
        if (st.getGlobalToolDefinitionId() == null) {
            throw new IllegalArgumentException("该扫描接口未关联全局 Tool");
        }
        Long gid = st.getGlobalToolDefinitionId();
        toolDefinitionService.findById(gid).ifPresent(ignored -> {
            semanticDocService.deleteByTool(gid);
            toolDefinitionService.deleteNonCodeToolById(gid);
        });
        st.setGlobalToolDefinitionId(null);
        // updateById 在默认策略下会忽略 null，导致库中 global_tool_definition_id 未清空，列表仍显示「已添加为 Tool」
        mapper.update(null, Wrappers.<ScanProjectToolEntity>lambdaUpdate()
                .set(ScanProjectToolEntity::getGlobalToolDefinitionId, null)
                .eq(ScanProjectToolEntity::getId, st.getId()));
        return st;
    }

    /**
     * 用当前扫描行内容覆盖已关联的 {@code tool_definition}（全局名不变）。
     */
    @Transactional
    public ToolDefinitionEntity pushScanToGlobalTool(Long projectId, Long scanToolId) {
        ScanProjectToolEntity st = findByProjectAndId(projectId, scanToolId)
                .orElseThrow(() -> new IllegalArgumentException("扫描接口不存在: " + scanToolId));
        if (st.getGlobalToolDefinitionId() == null) {
            throw new IllegalArgumentException("该扫描接口未关联全局 Tool");
        }
        ToolDefinitionEntity g = toolDefinitionService.findById(st.getGlobalToolDefinitionId())
                .orElseThrow(() -> new IllegalStateException("关联的全局 Tool 已不存在，请从 Tool 中下架后重新添加"));
        List<ToolDefinitionParameter> parameters = parseParameters(st.getParametersJson());
        String inferredSideEffect = SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath());
        ToolDefinitionUpsertRequest req = new ToolDefinitionUpsertRequest(
                g.getName(),
                "TOOL",
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
                Boolean.TRUE.equals(st.getLightweightEnabled()),
                inferredSideEffect,
                null,
                null
        );
        ToolDefinitionEntity updated = toolDefinitionService.update(g.getName(), req);
        syncAiDescriptionToGlobalTool(st, updated.getId());
        return toolDefinitionService.findById(updated.getId()).orElse(updated);
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
