package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.semantic.SemanticDocService;
import com.enterprise.ai.agent.semantic.SemanticMarkdownUtil;
import com.enterprise.ai.agent.domain.DomainAssignmentService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionService;
import com.enterprise.ai.agent.tools.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpAiTool;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpToolBaseUrlSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 扫描项目内接口的持久化与「注册为全局 Tool」。
 */
@Slf4j
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
    private final DomainAssignmentService domainAssignmentService;

    public ScanProjectToolService(ScanProjectToolMapper mapper,
                                  ScanProjectMapper projectMapper,
                                  ToolDefinitionService toolDefinitionService,
                                  SemanticDocService semanticDocService,
                                  ObjectMapper objectMapper,
                                  DomainAssignmentService domainAssignmentService) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.toolDefinitionService = toolDefinitionService;
        this.semanticDocService = semanticDocService;
        this.objectMapper = objectMapper;
        this.domainAssignmentService = domainAssignmentService;
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

    public Optional<ScanProjectToolEntity> findByProjectAndName(Long projectId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        ScanProjectToolEntity e = mapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(ScanProjectToolEntity::getName, name.trim())
                .last("limit 1"));
        return Optional.ofNullable(e);
    }

    /**
     * 增量/合并扫描：同项目内同工具名则按请求覆盖（用于重新扫描不删库时的 upsert）。
     */
    @Transactional
    public ScanProjectToolEntity upsertScanned(Long projectId, ToolDefinitionUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        Optional<ScanProjectToolEntity> existing = findByProjectAndName(projectId, request.name().trim());
        if (existing.isEmpty()) {
            return insertScanned(projectId, request);
        }
        return update(projectId, existing.get().getId(), request);
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
        existing.setRemovedFromSource(false);
        existing.setRemovedAt(null);
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
                            old.children(),
                            incoming.metadata()
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
        ).withCapabilityMetadata(request.capabilityMetadata());
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
        if (Boolean.TRUE.equals(st.getRemovedFromSource())) {
            throw new IllegalArgumentException("该接口已从源码/SDK 中移除，无法执行测试调用");
        }
        ScanProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IllegalStateException("扫描项目不存在: projectId=" + projectId);
        }
        ToolDefinitionEntity proxy = ScanProjectToolAdapter.toDefinitionEntity(st);
        proxy.setBaseUrl(DynamicHttpToolBaseUrlSupport.resolveEffectiveBaseUrl(proxy.getBaseUrl(), project));
        DynamicHttpToolBaseUrlSupport.requireValidRestClientBaseUrl(proxy.getBaseUrl());
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
        w.and(q -> q.isNull(ScanProjectToolEntity::getRemovedFromSource).or().eq(ScanProjectToolEntity::getRemovedFromSource, false));
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
        if (Boolean.TRUE.equals(st.getRemovedFromSource())) {
            throw new IllegalArgumentException("该接口已从业务源码或 SDK 上报中移除（墓碑行），请先在业务系统恢复该能力并同步目录后，再添加为 Tool");
        }
        if (st.getGlobalToolDefinitionId() != null) {
            return toolDefinitionService.findById(st.getGlobalToolDefinitionId())
                    .orElseThrow(() -> new IllegalStateException("关联的全局 Tool 已不存在，请重新扫描后再次添加"));
        }
        String globalName = allocateUniqueGlobalName(st.getName());
        List<ToolDefinitionParameter> parameters = parseParameters(st.getParametersJson());
        String inferredSideEffect = declaredSideEffect(st.getCapabilityMetadataJson())
                .orElseGet(() -> SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath()));
        ScanProjectEntity project = projectMapper.selectById(projectId);
        String projectCode = project == null ? null : project.getProjectCode();
        String visibility = project == null || project.getVisibility() == null ? "PRIVATE" : project.getVisibility();
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
                projectCode,
                visibility,
                projectCode == null || projectCode.isBlank() ? null : projectCode + ":" + globalName,
                Boolean.TRUE.equals(st.getEnabled()),
                Boolean.TRUE.equals(st.getAgentVisible()),
                Boolean.TRUE.equals(st.getLightweightEnabled()),
                inferredSideEffect,
                null,
                null,
                false,
                parseCapabilityMetadata(st.getCapabilityMetadataJson())
        );
        ToolDefinitionEntity created = toolDefinitionService.create(req);
        semanticDocService.migrateScanToolDocsToGlobal(projectId, scanToolId, created.getId());
        syncAiDescriptionToGlobalTool(st, created.getId());
        st.setGlobalToolDefinitionId(created.getId());
        mapper.updateById(st);
        autoAssignProjectDomain(projectId, created.getName());
        return toolDefinitionService.findById(created.getId()).orElse(created);
    }

    /**
     * Phase P1：扫描期 → 全局 Tool 时，若所属 project 配置了 default_domain_code，
     * 自动写入 {@code domain_assignment} 一条 {@code source=AUTO_FROM_PROJECT} 的归属。
     */
    private void autoAssignProjectDomain(Long projectId, String toolName) {
        if (domainAssignmentService == null || projectId == null || toolName == null) return;
        try {
            ScanProjectEntity proj = projectMapper.selectById(projectId);
            if (proj == null || proj.getDefaultDomainCode() == null || proj.getDefaultDomainCode().isBlank()) {
                return;
            }
            domainAssignmentService.upsert("TOOL", toolName, proj.getDefaultDomainCode(), 1.0, "AUTO_FROM_PROJECT");
        } catch (Exception ex) {
            log.debug("[ScanProjectToolService] AUTO_FROM_PROJECT 归属失败（已忽略）: {}", ex.toString());
        }
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
        String inferred = declaredSideEffect(st.getCapabilityMetadataJson())
                .orElseGet(() -> SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath()));
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
        if (!Objects.equals(trimOrNull(st.getCapabilityMetadataJson()), trimOrNull(g.getCapabilityMetadataJson()))) {
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
        st.setRemovedFromSource(false);
        st.setRemovedAt(null);
        // updateById 在默认策略下会忽略 null，导致库中 global_tool_definition_id 未清空，列表仍显示「已添加为 Tool」
        mapper.update(null, Wrappers.<ScanProjectToolEntity>lambdaUpdate()
                .set(ScanProjectToolEntity::getGlobalToolDefinitionId, null)
                .set(ScanProjectToolEntity::getRemovedFromSource, false)
                .set(ScanProjectToolEntity::getRemovedAt, null)
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
        if (Boolean.TRUE.equals(st.getRemovedFromSource())) {
            throw new IllegalArgumentException("该接口已从源码/SDK 中移除，请使用业务侧恢复能力并同步目录后再更新全局 Tool");
        }
        if (st.getGlobalToolDefinitionId() == null) {
            throw new IllegalArgumentException("该扫描接口未关联全局 Tool");
        }
        ToolDefinitionEntity g = toolDefinitionService.findById(st.getGlobalToolDefinitionId())
                .orElseThrow(() -> new IllegalStateException("关联的全局 Tool 已不存在，请从 Tool 中下架后重新添加"));
        List<ToolDefinitionParameter> parameters = parseParameters(st.getParametersJson());
        String inferredSideEffect = declaredSideEffect(st.getCapabilityMetadataJson())
                .orElseGet(() -> SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath()));
        ScanProjectEntity project = projectMapper.selectById(projectId);
        String projectCode = project == null ? g.getProjectCode() : project.getProjectCode();
        String visibility = project == null || project.getVisibility() == null ? g.getVisibility() : project.getVisibility();
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
                projectCode,
                visibility,
                g.getQualifiedName(),
                Boolean.TRUE.equals(st.getEnabled()),
                Boolean.TRUE.equals(st.getAgentVisible()),
                Boolean.TRUE.equals(st.getLightweightEnabled()),
                inferredSideEffect,
                null,
                null,
                false,
                parseCapabilityMetadata(st.getCapabilityMetadataJson())
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
        e.setCapabilityMetadataJson(serializeCapabilityMetadata(r.capabilityMetadata()));
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

    private Object parseCapabilityMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法解析能力声明元数据 JSON", ex);
        }
    }

    private Optional<String> declaredSideEffect(String metadataJson) {
        Object metadata = parseCapabilityMetadata(metadataJson);
        if (!(metadata instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Object raw = map.get("sideEffect");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(raw).trim().toUpperCase(Locale.ROOT));
    }

    private String serializeCapabilityMetadata(Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化能力声明元数据", ex);
        }
    }

    public Optional<ScanProjectToolEntity> findByProjectAndGlobalToolId(Long projectId, Long globalToolDefinitionId) {
        if (projectId == null || globalToolDefinitionId == null) {
            return Optional.empty();
        }
        ScanProjectToolEntity e = mapper.selectOne(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .eq(ScanProjectToolEntity::getGlobalToolDefinitionId, globalToolDefinitionId)
                .last("LIMIT 1"));
        return Optional.ofNullable(e);
    }

    /**
     * 将 SDK 同步后的 {@link ToolDefinitionEntity} 镜像到 {@code scan_project_tool}，供管理端「项目 API 目录」统一展示。
     */
    @Transactional
    public void syncMirrorFromSdkTool(Long projectId, ToolDefinitionEntity g, boolean markRemoved) {
        if (g == null || projectId == null) {
            return;
        }
        Optional<ScanProjectToolEntity> byGlobal = findByProjectAndGlobalToolId(projectId, g.getId());
        ScanProjectToolEntity e = byGlobal.orElseGet(() -> findByProjectAndName(projectId, g.getName()).orElse(null));
        if (e == null) {
            e = new ScanProjectToolEntity();
            e.setProjectId(projectId);
            e.setModuleId(null);
            e.setName(g.getName());
            copyGlobalToolFieldsToScanRow(e, g);
            e.setGlobalToolDefinitionId(g.getId());
            e.setRemovedFromSource(markRemoved);
            e.setRemovedAt(markRemoved ? LocalDateTime.now() : null);
            mapper.insert(e);
            return;
        }
        copyGlobalToolFieldsToScanRow(e, g);
        e.setGlobalToolDefinitionId(g.getId());
        e.setRemovedFromSource(markRemoved);
        e.setRemovedAt(markRemoved ? LocalDateTime.now() : null);
        mapper.updateById(e);
    }

    private void copyGlobalToolFieldsToScanRow(ScanProjectToolEntity e, ToolDefinitionEntity g) {
        e.setDescription(StringUtils.hasText(g.getDescription()) ? g.getDescription() : "");
        e.setParametersJson(g.getParametersJson());
        e.setSource(StringUtils.hasText(g.getSource()) ? g.getSource().trim() : SOURCE_SCANNER);
        e.setSourceLocation(g.getSourceLocation());
        e.setHttpMethod(g.getHttpMethod());
        e.setBaseUrl(g.getBaseUrl());
        e.setContextPath(g.getContextPath());
        e.setEndpointPath(g.getEndpointPath());
        e.setRequestBodyType(g.getRequestBodyType());
        e.setResponseType(g.getResponseType());
        e.setAiDescription(g.getAiDescription());
        e.setCapabilityMetadataJson(g.getCapabilityMetadataJson());
        e.setEnabled(g.getEnabled() == null || Boolean.TRUE.equals(g.getEnabled()));
        e.setAgentVisible(g.getAgentVisible() == null || Boolean.TRUE.equals(g.getAgentVisible()));
        e.setLightweightEnabled(Boolean.TRUE.equals(g.getLightweightEnabled()));
    }

    /**
     * 为尚未有镜像行的 SDK 能力补齐 {@code scan_project_tool}。
     *
     * @return 新插入的镜像行数量
     */
    @Transactional
    public int ensureSdkMirrors(Long projectId) {
        ScanProjectEntity project = projectMapper.selectById(projectId);
        if (project == null || !StringUtils.hasText(project.getProjectCode())) {
            return 0;
        }
        int added = 0;
        for (ToolDefinitionEntity t : toolDefinitionService.listByProjectId(projectId)) {
            if (t.getKind() != null && !ToolDefinitionService.KIND_TOOL.equalsIgnoreCase(t.getKind())) {
                continue;
            }
            if (!isSdkBackedTool(project, t)) {
                continue;
            }
            if (findByProjectAndGlobalToolId(projectId, t.getId()).isPresent()) {
                continue;
            }
            syncMirrorFromSdkTool(projectId, t, false);
            added++;
        }
        return added;
    }

    public static boolean isSdkBackedTool(ScanProjectEntity project, ToolDefinitionEntity existing) {
        if (project == null || existing == null || !StringUtils.hasText(project.getProjectCode())) {
            return false;
        }
        if (existing.getSourceLocation() == null) {
            return false;
        }
        String prefix = "sdk:" + project.getProjectCode().trim() + ":";
        return existing.getSourceLocation().startsWith(prefix)
                && Objects.equals(existing.getProjectId(), project.getId());
    }

    public List<String> divergenceFieldNames(ScanProjectToolEntity st, ToolDefinitionEntity g) {
        List<String> out = new ArrayList<>();
        if (g == null) {
            return out;
        }
        String kind = g.getKind();
        if (kind == null || kind.isBlank()) {
            kind = ToolDefinitionService.KIND_TOOL;
        }
        if (!ToolDefinitionService.KIND_TOOL.equalsIgnoreCase(kind)) {
            out.add("kind");
            return out;
        }
        if (!Objects.equals(trimOrNull(st.getDescription()), trimOrNull(g.getDescription()))) {
            out.add("description");
        }
        if (!Objects.equals(parseParameters(st.getParametersJson()), parseParameters(g.getParametersJson()))) {
            out.add("parameters");
        }
        if (!Objects.equals(trimOrNull(st.getSourceLocation()), trimOrNull(g.getSourceLocation()))) {
            out.add("sourceLocation");
        }
        if (!Objects.equals(trimOrNull(st.getHttpMethod()), trimOrNull(g.getHttpMethod()))) {
            out.add("httpMethod");
        }
        if (!Objects.equals(trimOrNull(st.getBaseUrl()), trimOrNull(g.getBaseUrl()))) {
            out.add("baseUrl");
        }
        if (!Objects.equals(trimOrNull(st.getContextPath()), trimOrNull(g.getContextPath()))) {
            out.add("contextPath");
        }
        if (!Objects.equals(trimOrNull(st.getEndpointPath()), trimOrNull(g.getEndpointPath()))) {
            out.add("endpointPath");
        }
        if (!Objects.equals(trimOrNull(st.getRequestBodyType()), trimOrNull(g.getRequestBodyType()))) {
            out.add("requestBodyType");
        }
        if (!Objects.equals(trimOrNull(st.getResponseType()), trimOrNull(g.getResponseType()))) {
            out.add("responseType");
        }
        if (Boolean.TRUE.equals(st.getEnabled()) != Boolean.TRUE.equals(g.getEnabled())) {
            out.add("enabled");
        }
        if (Boolean.TRUE.equals(st.getAgentVisible()) != Boolean.TRUE.equals(g.getAgentVisible())) {
            out.add("agentVisible");
        }
        if (Boolean.TRUE.equals(st.getLightweightEnabled()) != Boolean.TRUE.equals(g.getLightweightEnabled())) {
            out.add("lightweightEnabled");
        }
        if (!Objects.equals(st.getProjectId(), g.getProjectId())) {
            out.add("projectId");
        }
        String inferred = declaredSideEffect(st.getCapabilityMetadataJson())
                .orElseGet(() -> SideEffectInferrer.inferAsString(st.getHttpMethod(), st.getEndpointPath()));
        String gSide = g.getSideEffect() == null || g.getSideEffect().isBlank()
                ? "WRITE"
                : g.getSideEffect().trim().toUpperCase(Locale.ROOT);
        String iSide = inferred == null || inferred.isBlank()
                ? "WRITE"
                : inferred.trim().toUpperCase(Locale.ROOT);
        if (!Objects.equals(iSide, gSide)) {
            out.add("sideEffect");
        }
        if (!Objects.equals(trimOrNull(st.getAiDescription()), trimOrNull(g.getAiDescription()))) {
            out.add("aiDescription");
        }
        if (!Objects.equals(trimOrNull(st.getCapabilityMetadataJson()), trimOrNull(g.getCapabilityMetadataJson()))) {
            out.add("capabilityMetadata");
        }
        return out;
    }

    public LinkState resolveLinkState(ScanProjectToolEntity entity,
                                      ToolDefinitionEntity global,
                                      Set<String> pendingSdkQualifiedNames) {
        String qn = global != null ? trimOrNull(global.getQualifiedName()) : null;
        boolean sdkPending = qn != null && pendingSdkQualifiedNames != null && pendingSdkQualifiedNames.contains(qn);
        if (Boolean.TRUE.equals(entity.getRemovedFromSource())) {
            return new LinkState(ApiToolLinkStatus.API_REMOVED_STALE,
                    "接口在 SDK/扫描源中已不存在，全局 Tool 可能仍存在（可能已被禁用）",
                    List.of(), sdkPending);
        }
        Long gid = entity.getGlobalToolDefinitionId();
        if (gid == null) {
            return new LinkState(ApiToolLinkStatus.NOT_LINKED, null, List.of(), sdkPending);
        }
        if (global == null) {
            return new LinkState(ApiToolLinkStatus.GLOBAL_MISSING,
                    "关联的全局 Tool 已删除，请「从 Tool 中下架」后重新添加。",
                    List.of(), sdkPending);
        }
        if (isScanDivergedFromGlobal(entity, global)) {
            return new LinkState(ApiToolLinkStatus.PENDING_UPDATE,
                    "项目 API 目录与全局 Tool 不一致，可执行「更新到 Tool」",
                    divergenceFieldNames(entity, global), sdkPending);
        }
        return new LinkState(ApiToolLinkStatus.IN_SYNC, null, List.of(), sdkPending);
    }

    public ToolReconcileSummary reconcileCatalog(Long projectId, Set<String> pendingSdkQualifiedNames) {
        int ensured = ensureSdkMirrors(projectId);
        List<ScanProjectToolEntity> tools = listByProject(projectId);
        Set<Long> globalIds = tools.stream()
                .map(ScanProjectToolEntity::getGlobalToolDefinitionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ToolDefinitionEntity> globalById = toolDefinitionService.mapByIds(globalIds);
        int notLinked = 0;
        int inSync = 0;
        int pendingUpdate = 0;
        int apiRemovedStale = 0;
        int globalMissing = 0;
        int sdkReviewPendingRows = 0;
        Set<String> pending = pendingSdkQualifiedNames == null ? Set.of() : pendingSdkQualifiedNames;
        for (ScanProjectToolEntity e : tools) {
            Long gid = e.getGlobalToolDefinitionId();
            ToolDefinitionEntity g = gid == null ? null : globalById.get(gid);
            if (g == null && gid != null) {
                g = toolDefinitionService.findById(gid).orElse(null);
            }
            LinkState ls = resolveLinkState(e, g, pending);
            if (ls.sdkCapabilityReviewPending()) {
                sdkReviewPendingRows++;
            }
            switch (ls.status()) {
                case NOT_LINKED -> notLinked++;
                case IN_SYNC -> inSync++;
                case PENDING_UPDATE -> pendingUpdate++;
                case API_REMOVED_STALE -> apiRemovedStale++;
                case GLOBAL_MISSING -> globalMissing++;
            }
        }
        return new ToolReconcileSummary(ensured, notLinked, inSync, pendingUpdate, apiRemovedStale, globalMissing, sdkReviewPendingRows);
    }

    public record LinkState(ApiToolLinkStatus status,
                            String message,
                            List<String> diffFields,
                            boolean sdkCapabilityReviewPending) {
    }

    public record ToolReconcileSummary(int sdkMirrorsEnsured,
                                       int notLinked,
                                       int inSync,
                                       int pendingUpdate,
                                       int apiRemovedStale,
                                       int globalMissing,
                                       int sdkReviewPendingRows) {
    }
}
