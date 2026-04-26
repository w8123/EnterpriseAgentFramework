package com.enterprise.ai.agent.tools.definition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.skill.SubAgentSkill;
import com.enterprise.ai.agent.skill.SubAgentSkillFactory;
import com.enterprise.ai.agent.skill.SubAgentSpec;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormSkill;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormSkillFactory;
import com.enterprise.ai.agent.skill.interactive.InteractiveFormSpec;
import com.enterprise.ai.agent.scan.ScanProjectAuthSupport;
import com.enterprise.ai.agent.scan.ScanProjectEntity;
import com.enterprise.ai.agent.scan.ScanProjectMapper;
import com.enterprise.ai.agent.scan.SideEffectInferrer;
import com.enterprise.ai.agent.tool.retrieval.ToolEmbeddingService;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.dynamic.DynamicHttpAiTool;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolDefinitionService {

    private static final TypeReference<List<ToolDefinitionParameter>> PARAMETER_LIST_TYPE = new TypeReference<>() {
    };
    private static final String SOURCE_CODE = "code";
    private static final String SOURCE_SCANNER = "scanner";
    private static final String SOURCE_MANUAL = "manual";

    public static final String KIND_TOOL = "TOOL";
    public static final String KIND_SKILL = "SKILL";

    private final ToolDefinitionMapper mapper;
    private final ScanProjectMapper scanProjectMapper;
    private final ToolRegistry toolRegistry;
    private final List<AiTool> codeTools;
    private final ObjectMapper objectMapper;
    private final ToolEmbeddingService toolEmbeddingService;
    private final SubAgentSkillFactory subAgentSkillFactory;
    private final InteractiveFormSkillFactory interactiveFormSkillFactory;

    public ToolDefinitionService(ToolDefinitionMapper mapper,
                                 ScanProjectMapper scanProjectMapper,
                                 ToolRegistry toolRegistry,
                                 List<AiTool> codeTools,
                                 ObjectMapper objectMapper,
                                 ToolEmbeddingService toolEmbeddingService,
                                 @Lazy SubAgentSkillFactory subAgentSkillFactory,
                                 @Lazy InteractiveFormSkillFactory interactiveFormSkillFactory) {
        this.mapper = mapper;
        this.scanProjectMapper = scanProjectMapper;
        this.toolRegistry = toolRegistry;
        this.codeTools = codeTools;
        this.objectMapper = objectMapper;
        this.toolEmbeddingService = toolEmbeddingService;
        this.subAgentSkillFactory = subAgentSkillFactory;
        this.interactiveFormSkillFactory = interactiveFormSkillFactory;
    }

    @PostConstruct
    public void initialize() {
        syncCodeTools();
        loadEnabledConfigTools();
    }

    public void syncCodeTools() {
        Set<String> currentNames = new LinkedHashSet<>();
        for (AiTool tool : codeTools) {
            // Skill 通过 DB 加载，不在 codeTools 里；这里只同步纯代码 Tool。
            if (tool instanceof com.enterprise.ai.skill.AiSkill) {
                continue;
            }
            currentNames.add(tool.name());
            ToolDefinitionEntity existing = findByName(tool.name()).orElse(null);
            ToolDefinitionEntity entity = existing == null ? new ToolDefinitionEntity() : existing;
            entity.setName(tool.name());
            entity.setKind(KIND_TOOL);
            entity.setDescription(tool.description());
            entity.setParametersJson(serializeParameters(tool.parameters().stream()
                    .map(this::toStoredParameter)
                    .toList()));
            entity.setSource(SOURCE_CODE);
            entity.setSourceLocation(tool.getClass().getName());
            entity.setProjectId(null);

            if (existing == null) {
                entity.setEnabled(true);
                entity.setAgentVisible(true);
                entity.setLightweightEnabled("search_knowledge".equals(tool.name()));
                entity.setSideEffect("READ_ONLY");
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

    /**
     * 分页查询，支持按关键词（名称/描述 模糊匹配）、来源、是否启用、扫描项目过滤。
     */
    public IPage<ToolDefinitionEntity> page(
            int current,
            int size,
            String keyword,
            String source,
            Boolean enabled,
            Long projectId) {
        int pageNum = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        Page<ToolDefinitionEntity> p = new Page<>(pageNum, pageSize, true);
        LambdaQueryWrapper<ToolDefinitionEntity> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String k = keyword.trim();
            w.and(q -> q.like(ToolDefinitionEntity::getName, k)
                    .or()
                    .like(ToolDefinitionEntity::getDescription, k));
        }
        if (StringUtils.hasText(source)) {
            w.eq(ToolDefinitionEntity::getSource, source.trim().toLowerCase());
        }
        if (enabled != null) {
            w.eq(ToolDefinitionEntity::getEnabled, enabled);
        }
        if (projectId != null) {
            w.eq(ToolDefinitionEntity::getProjectId, projectId);
        }
        w.orderByAsc(ToolDefinitionEntity::getName);
        return mapper.selectPage(p, w);
    }

    public List<ToolDefinitionEntity> listByProjectId(Long projectId) {
        return mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getProjectId, projectId)
                .orderByAsc(ToolDefinitionEntity::getName));
    }

    /**
     * 一次性回填副作用等级：
     * - 仅处理 kind=TOOL（Skill 由运营侧明确配置）
     * - 默认只覆盖 side_effect 为空或 WRITE 的记录，避免误伤人工已校准值
     *
     * @return 实际更新条数
     */
    public int backfillSideEffectsForTools() {
        List<ToolDefinitionEntity> tools = mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getKind, KIND_TOOL));
        int updated = 0;
        for (ToolDefinitionEntity tool : tools) {
            if (!shouldBackfillSideEffect(tool.getSideEffect())) {
                continue;
            }
            String inferred = SideEffectInferrer.inferAsString(tool.getHttpMethod(), tool.getEndpointPath());
            if (!inferred.equalsIgnoreCase(normalizeSideEffect(tool.getSideEffect()))) {
                tool.setSideEffect(inferred);
                mapper.updateById(tool);
                updated++;
            }
        }
        return updated;
    }

    public Optional<ToolDefinitionEntity> findByName(String name) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getName, name)
                .last("limit 1")));
    }

    public Optional<ToolDefinitionEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(id));
    }

    public List<ToolDefinitionEntity> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>().in(ToolDefinitionEntity::getId, ids));
    }

    public Map<Long, ToolDefinitionEntity> mapByIds(Collection<Long> ids) {
        return listByIds(ids).stream()
                .collect(Collectors.toMap(ToolDefinitionEntity::getId, e -> e, (a, b) -> a));
    }

    /**
     * 写入 {@code ai_description} 并重建向量索引（与语义文档 pipeline 一致）。
     */
    public void setAiDescriptionAndReindex(Long id, String aiDescription) {
        if (id == null) {
            return;
        }
        ToolDefinitionEntity e = mapper.selectById(id);
        if (e == null) {
            return;
        }
        e.setAiDescription(aiDescription);
        mapper.updateById(e);
        toolEmbeddingService.upsert(e);
        registerIfNeeded(e);
    }

    public ToolDefinitionEntity create(ToolDefinitionUpsertRequest request) {
        validateRequest(request, false);
        if (findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("工具已存在: " + request.name());
        }
        ToolDefinitionEntity entity = applyRequest(new ToolDefinitionEntity(), request, false);
        mapper.insert(entity);
        registerIfNeeded(entity);
        toolEmbeddingService.upsert(entity);
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
            toolEmbeddingService.upsert(existing);
            return existing;
        }

        ToolDefinitionEntity updated = applyRequest(existing, request, true);
        updated.setId(existing.getId());
        updated.setSource(existing.getSource());
        mapper.updateById(updated);
        registerIfNeeded(updated);
        toolEmbeddingService.upsert(updated);
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
        boolean removed = mapper.deleteById(existing.getId()) > 0;
        if (removed) {
            toolRegistry.remove(name);
            toolEmbeddingService.delete(existing.getId());
        }
        return removed;
    }

    /**
     * 按主键删除非 code 类 Tool（如 scanner / manual），并同步 registry 与向量索引（用于扫描接口「从 Tool 下架」）。
     */
    public boolean deleteNonCodeToolById(Long id) {
        if (id == null) {
            return false;
        }
        ToolDefinitionEntity existing = mapper.selectById(id);
        if (existing == null) {
            return false;
        }
        if (SOURCE_CODE.equals(existing.getSource())) {
            throw new IllegalArgumentException("Code Tool 不可删除，只能禁用");
        }
        String name = existing.getName();
        boolean removed = mapper.deleteById(id) > 0;
        if (removed) {
            toolRegistry.remove(name);
            toolEmbeddingService.delete(id);
        }
        return removed;
    }

    public boolean deleteByProjectId(Long projectId) {
        List<ToolDefinitionEntity> entities = listByProjectId(projectId);
        if (entities.isEmpty()) {
            return false;
        }
        for (ToolDefinitionEntity entity : entities) {
            if (SOURCE_CODE.equals(entity.getSource())) {
                continue;
            }
            mapper.deleteById(entity.getId());
            toolRegistry.remove(entity.getName());
            toolEmbeddingService.delete(entity.getId());
        }
        return true;
    }

    public ToolDefinitionEntity toggle(String name, boolean enabled) {
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
        if (Boolean.TRUE.equals(existing.getDraft()) && enabled) {
            throw new IllegalArgumentException("草稿请先「发布」（保存且 draft=false）后再启用");
        }
        existing.setEnabled(enabled);
        mapper.updateById(existing);
        if (enabled) {
            registerIfNeeded(existing);
        } else {
            // 禁用时立刻从 registry 摘除，Agent 下次 createToolkit 就看不到它
            if (!SOURCE_CODE.equals(existing.getSource())) {
                toolRegistry.remove(existing.getName());
            }
        }
        toolEmbeddingService.upsert(existing);
        return existing;
    }

    public Object executeTool(String name, Map<String, Object> args) {
        ToolDefinitionEntity existing = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
        if (Boolean.TRUE.equals(existing.getDraft())) {
            throw new IllegalArgumentException("草稿 Skill 不可执行或测试");
        }
        if (KIND_SKILL.equalsIgnoreCase(existing.getKind())) {
            String sk = existing.getSkillKind();
            if (sk != null && InteractiveFormSkillFactory.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(sk.trim())) {
                InteractiveFormSkill skill = interactiveFormSkillFactory.build(existing);
                return skill.execute(args == null ? Map.of() : args);
            }
            SubAgentSkill skill = subAgentSkillFactory.build(existing);
            return skill.execute(args == null ? Map.of() : args);
        }
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
                .filter(entity -> !Boolean.TRUE.equals(entity.getDraft()))
                .filter(entity -> Boolean.TRUE.equals(entity.getEnabled()))
                .filter(entity -> Boolean.TRUE.equals(entity.getAgentVisible()))
                .isPresent();
    }

    public boolean isSkill(String name) {
        return findByName(name)
                .filter(entity -> KIND_SKILL.equalsIgnoreCase(entity.getKind()))
                .isPresent();
    }

    /** 获取所有 Skill（kind=SKILL）。用于 SkillController 列表页。 */
    public List<ToolDefinitionEntity> listSkills() {
        return mapper.selectList(new LambdaQueryWrapper<ToolDefinitionEntity>()
                .eq(ToolDefinitionEntity::getKind, KIND_SKILL)
                .orderByAsc(ToolDefinitionEntity::getName));
    }

    public IPage<ToolDefinitionEntity> pageSkills(int current, int size, String keyword, Boolean enabled,
                                                  Boolean draft) {
        int pageNum = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        Page<ToolDefinitionEntity> p = new Page<>(pageNum, pageSize, true);
        LambdaQueryWrapper<ToolDefinitionEntity> w = new LambdaQueryWrapper<>();
        w.eq(ToolDefinitionEntity::getKind, KIND_SKILL);
        if (StringUtils.hasText(keyword)) {
            String k = keyword.trim();
            w.and(q -> q.like(ToolDefinitionEntity::getName, k)
                    .or()
                    .like(ToolDefinitionEntity::getDescription, k));
        }
        if (enabled != null) {
            w.eq(ToolDefinitionEntity::getEnabled, enabled);
        }
        if (draft != null) {
            w.eq(ToolDefinitionEntity::getDraft, draft);
        }
        w.orderByAsc(ToolDefinitionEntity::getName);
        return mapper.selectPage(p, w);
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
        String kind = normalizeKind(request.kind());
        entity.setName(updating ? entity.getName() : request.name());
        entity.setKind(kind);
        String desc = request.description();
        if (KIND_SKILL.equals(kind) && Boolean.TRUE.equals(request.draft()) && isBlank(desc)) {
            desc = "（草稿）";
        }
        entity.setDescription(desc);
        entity.setParametersJson(serializeParameters(request.parameters()));
        entity.setSource(normalizeSource(request.source()));
        entity.setSourceLocation(request.sourceLocation());
        entity.setProjectId(request.projectId());
        entity.setAgentVisible(request.agentVisible());
        entity.setLightweightEnabled(request.lightweightEnabled());
        entity.setSideEffect(normalizeSideEffect(request.sideEffect()));

        if (KIND_SKILL.equals(kind)) {
            entity.setDraft(Boolean.TRUE.equals(request.draft()));
            if (Boolean.TRUE.equals(entity.getDraft())) {
                entity.setEnabled(false);
            } else {
                entity.setEnabled(request.enabled());
            }
        } else {
            entity.setDraft(false);
            entity.setEnabled(request.enabled());
        }

        if (KIND_SKILL.equals(kind)) {
            // Skill: 不落 HTTP 字段，保存 spec_json / skill_kind
            entity.setHttpMethod(null);
            entity.setBaseUrl(null);
            entity.setContextPath(null);
            entity.setEndpointPath(null);
            entity.setRequestBodyType(null);
            entity.setResponseType(null);
            entity.setSkillKind(isBlank(request.skillKind())
                    ? SubAgentSkillFactory.SKILL_KIND_SUB_AGENT
                    : request.skillKind().trim().toUpperCase());
            entity.setSpecJson(request.specJson());
        } else {
            entity.setHttpMethod(request.httpMethod());
            entity.setBaseUrl(request.baseUrl());
            entity.setContextPath(request.contextPath());
            entity.setEndpointPath(request.endpointPath());
            entity.setRequestBodyType(request.requestBodyType());
            entity.setResponseType(request.responseType());
            entity.setSkillKind(null);
            entity.setSpecJson(null);
        }
        return entity;
    }

    private void validateRequest(ToolDefinitionUpsertRequest request, boolean updating) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (!updating && isBlank(request.name())) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        String kind = normalizeKind(request.kind());
        if (KIND_SKILL.equals(kind) && Boolean.TRUE.equals(request.draft())) {
            return;
        }
        if (isBlank(request.description())) {
            throw new IllegalArgumentException("工具描述不能为空");
        }
        String source = normalizeSource(request.source());

        if (KIND_SKILL.equals(kind)) {
            // Skill 校验
            if (isBlank(request.specJson())) {
                throw new IllegalArgumentException("Skill 必须提供 specJson");
            }
            String skillKind = request.skillKind();
            if (skillKind != null
                    && InteractiveFormSkillFactory.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(skillKind.trim())) {
                InteractiveFormSpec parsed = interactiveFormSkillFactory.parseSpec(request.specJson());
                interactiveFormSkillFactory.validateSpec(
                        updating ? request.name() : request.name(), parsed);
                return;
            }
            SubAgentSpec parsed = subAgentSkillFactory.parseSpec(request.specJson());
            subAgentSkillFactory.validateSpec(
                    updating ? request.name() : request.name(), parsed);
            return;
        }

        // 下方仅对 TOOL 生效
        if (!updating && SOURCE_CODE.equals(source)) {
            throw new IllegalArgumentException("不允许手工创建 code 类型工具");
        }
        if ((SOURCE_MANUAL.equals(source) || SOURCE_SCANNER.equals(source)) && isBlank(request.httpMethod())) {
            throw new IllegalArgumentException("HTTP 工具必须提供 httpMethod");
        }
    }

    private String normalizeKind(String kind) {
        if (isBlank(kind)) {
            return KIND_TOOL;
        }
        String normalized = kind.trim().toUpperCase();
        if (!KIND_TOOL.equals(normalized) && !KIND_SKILL.equals(normalized)) {
            throw new IllegalArgumentException("未知 kind: " + kind);
        }
        return normalized;
    }

    private String normalizeSideEffect(String raw) {
        if (isBlank(raw)) {
            return "WRITE";
        }
        return raw.trim().toUpperCase();
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
        if (Boolean.TRUE.equals(entity.getDraft())) {
            if (KIND_SKILL.equalsIgnoreCase(entity.getKind())) {
                toolRegistry.remove(entity.getName());
            }
            return;
        }
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            return;
        }
        if (KIND_SKILL.equalsIgnoreCase(entity.getKind())) {
            try {
                if (entity.getSkillKind() != null
                        && InteractiveFormSkillFactory.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(
                        entity.getSkillKind().trim())) {
                    InteractiveFormSkill skill = interactiveFormSkillFactory.build(entity);
                    toolRegistry.register(skill);
                } else {
                    SubAgentSkill skill = subAgentSkillFactory.build(entity);
                    toolRegistry.register(skill);
                }
                log.debug("[ToolDefinitionService] 注册 Skill: name={}, kind={}",
                        entity.getName(), entity.getSkillKind());
            } catch (Exception ex) {
                log.warn("[ToolDefinitionService] Skill 注册失败（跳过，不阻塞启动）: name={}, err={}",
                        entity.getName(), ex.toString());
            }
            return;
        }
        if (SOURCE_CODE.equals(entity.getSource())) {
            return;
        }
        toolRegistry.register(buildDynamicTool(entity));
    }

    private DynamicHttpAiTool buildDynamicTool(ToolDefinitionEntity entity) {
        if (entity.getProjectId() != null) {
            ScanProjectEntity project = scanProjectMapper.selectById(entity.getProjectId());
            var extras = ScanProjectAuthSupport.invocationExtras(project);
            return new DynamicHttpAiTool(entity, objectMapper, extras);
        }
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

    private boolean shouldBackfillSideEffect(String sideEffect) {
        return isBlank(sideEffect) || "WRITE".equalsIgnoreCase(sideEffect);
    }
}
