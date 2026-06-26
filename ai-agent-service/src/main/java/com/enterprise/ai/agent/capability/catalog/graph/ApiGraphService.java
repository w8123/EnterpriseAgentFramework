package com.enterprise.ai.agent.capability.catalog.graph;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleService;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolService;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocService;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 接口图谱核心服务。
 * <p>一期能力：</p>
 * <ul>
 *   <li>{@link #rebuildForProject} —— 投影 API / FIELD / DTO / MODULE 节点（扫描后是否调用由 {@code ai.api-graph.rebuild-on-scan} 控制）</li>
 *   <li>{@link #inferModelRefEdges} —— 自动生成「数据模型共享」MODEL_REF 紫色虚线边</li>
 *   <li>{@link #upsertManualEdge} / {@link #deleteEdge} —— 运营手动连线 / 删边</li>
 *   <li>{@link #loadGraph} —— 拉取 {nodes, edges, layout} 给前端 G6 渲染</li>
 *   <li>{@link #saveLayout} —— 保存运营布局</li>
 *   <li>{@link #regenerateGraphInteractive} —— 清空后全量再生（管理端）</li>
 *   <li>{@link #deleteByProject} —— 项目删除时联动清理</li>
 * </ul>
 *
 * <p>所有数据访问统一走 {@link ApiGraphRepository}，方便二期换图 DB 副本。</p>
 */
@Slf4j
@Service
public class ApiGraphService {

    /** 基础类型集合：不会为其建独立 DTO 节点，也不参与 MODEL_REF 共享边推断。 */
    private static final Set<String> PRIMITIVE_TYPE_NAMES = Set.of(
            "string", "integer", "int", "long", "short", "byte", "float", "double",
            "decimal", "number", "boolean", "bool", "date", "datetime", "time",
            "object", "json", "map", "any", "void", "null", "char"
    );

    private static final TypeReference<List<ToolDefinitionParameter>> PARAM_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ApiGraphRepository repository;
    private final ScanProjectToolService scanProjectToolService;
    private final ScanModuleService scanModuleService;
    private final SemanticDocService semanticDocService;
    private final ToolDefinitionService toolDefinitionService;
    private final ObjectMapper objectMapper;

    public ApiGraphService(ApiGraphRepository repository,
                           ScanProjectToolService scanProjectToolService,
                           ScanModuleService scanModuleService,
                           SemanticDocService semanticDocService,
                           ToolDefinitionService toolDefinitionService,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.scanProjectToolService = scanProjectToolService;
        this.scanModuleService = scanModuleService;
        this.semanticDocService = semanticDocService;
        this.toolDefinitionService = toolDefinitionService;
        this.objectMapper = objectMapper;
    }

    // ====================================================================================
    // 一、节点投影：从 scan_project_tool / scan_module / 参数树拉出节点并 upsert
    // ====================================================================================

    /**
     * 重新构建项目下的图谱节点（幂等），并自动推断 MODEL_REF 紫边。
     * 失败不向上抛出（旁路 hook，不影响扫描主链路）。
     */
    @Transactional
    public void rebuildForProject(Long projectId) {
        if (projectId == null) {
            return;
        }
        try {
            doRebuild(projectId);
        } catch (Exception ex) {
            log.warn("[ApiGraphService] rebuildForProject failed projectId={}", projectId, ex);
        }
    }

    /**
     * 管理端 / OpenAPI 「重建图谱」专用：失败抛出异常，便于接口返回明确错误。
     * 扫描链路仅在 {@code ai.api-graph.rebuild-on-scan=true} 时调用 {@link #rebuildForProject}。
     */
    @Transactional
    public void rebuildGraphInteractive(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        doRebuild(projectId);
    }

    /**
     * 管理端「重新生成」：删除本项目下全部图谱节点、边与画布布局后，再执行与 {@link #doRebuild} 相同的全量投影与 MODEL_REF 推断。
     * <p>手工连线、候选边、卡片坐标等均会丢失；扫描侧 {@link ScanProjectToolEntity} 数据不变。</p>
     */
    @Transactional
    public void regenerateGraphInteractive(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        repository.deleteByProject(projectId);
        doRebuild(projectId);
    }

    private void doRebuild(Long projectId) {
        repository.beginRebuildCaches(projectId);
        try {
            doRebuildBody(projectId);
        } finally {
            repository.endRebuildCaches();
        }
    }

    private void doRebuildBody(Long projectId) {
        List<ScanModuleEntity> modules = scanModuleService.listByProject(projectId);
        Map<Long, Long> moduleNodeIds = new LinkedHashMap<>();
        Set<Long> keepIds = new LinkedHashSet<>();
        for (ScanModuleEntity module : modules) {
            ApiGraphNodeEntity node = upsertModuleNode(projectId, module);
            keepIds.add(node.getId());
            moduleNodeIds.put(module.getId(), node.getId());
        }

        List<ScanProjectToolEntity> tools = scanProjectToolService.listByProject(projectId);
        // 同名 DTO 在项目内只建一个节点，避免每个引用处都重复建
        Map<String, ApiGraphNodeEntity> dtoNodeByType = new LinkedHashMap<>();

        for (ScanProjectToolEntity tool : tools) {
            ApiGraphNodeEntity apiNode = upsertApiNode(projectId, tool, moduleNodeIds.get(tool.getModuleId()));
            keepIds.add(apiNode.getId());

            List<ToolDefinitionParameter> parameters = parseParameters(tool.getParametersJson());
            List<ToolDefinitionParameter> ins = new ArrayList<>();
            ToolDefinitionParameter responseRoot = null;
            for (ToolDefinitionParameter p : parameters) {
                if (isResponseParameter(p)) {
                    responseRoot = p;
                } else {
                    ins.add(p);
                }
            }

            for (ToolDefinitionParameter p : ins) {
                walkAndUpsertField(projectId, apiNode.getId(), apiNode.getId(), p, "", ApiGraphNodeKind.FIELD_IN,
                        dtoNodeByType, keepIds);
            }
            if (responseRoot == null) {
                responseRoot = syntheticResponseFromTool(tool);
            }
            if (responseRoot != null) {
                List<ToolDefinitionParameter> outChildren = responseRoot.children();
                if (outChildren != null && !outChildren.isEmpty()) {
                    for (ToolDefinitionParameter child : outChildren) {
                        walkAndUpsertField(projectId, apiNode.getId(), apiNode.getId(), child, "",
                                ApiGraphNodeKind.FIELD_OUT, dtoNodeByType, keepIds);
                    }
                } else {
                    // response 直接是一个 DTO，建一个 FIELD_OUT 占位
                    walkAndUpsertField(projectId, apiNode.getId(), apiNode.getId(), responseRoot, "",
                            ApiGraphNodeKind.FIELD_OUT, dtoNodeByType, keepIds);
                }
            }
        }

        for (ApiGraphNodeEntity dtoNode : dtoNodeByType.values()) {
            keepIds.add(dtoNode.getId());
        }

        // Controller 扫描等仅有 responseType、无 RESPONSE 子参树时，复合出参只有一层节点；从同类型已展开字段克隆子树
        expandBareCompositeFields(projectId, keepIds);

        repository.deleteNodesNotIn(projectId, keepIds);

        // MODEL_REF auto 边重新推断（manual 边永不被覆盖）
        inferModelRefEdges(projectId);
    }

    private ApiGraphNodeEntity upsertModuleNode(Long projectId, ScanModuleEntity module) {
        ApiGraphNodeEntity node = new ApiGraphNodeEntity();
        node.setProjectId(projectId);
        node.setKind(ApiGraphNodeKind.MODULE);
        node.setRefId(module.getId());
        node.setParentId(null);
        node.setLabel(safeNonBlank(module.getDisplayName(), module.getName(), "module-" + module.getId()));
        node.setTypeName(null);
        node.setPropsJson(writeJsonOrNull(Map.of(
                "name", safeNonNull(module.getName()),
                "displayName", safeNonNull(module.getDisplayName())
        )));
        return repository.upsertNode(node);
    }

    private ApiGraphNodeEntity upsertApiNode(Long projectId, ScanProjectToolEntity tool, Long moduleNodeId) {
        ApiGraphNodeEntity node = new ApiGraphNodeEntity();
        node.setProjectId(projectId);
        node.setKind(ApiGraphNodeKind.API);
        node.setRefId(tool.getId());
        node.setParentId(moduleNodeId); // API 隶属模块节点（用于布局分组）
        node.setLabel(safeNonBlank(tool.getName(), "api-" + tool.getId()));
        node.setTypeName(null);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scanToolId", tool.getId());
        props.put("httpMethod", safeNonNull(tool.getHttpMethod()));
        props.put("endpointPath", safeNonNull(tool.getEndpointPath()));
        props.put("contextPath", safeNonNull(tool.getContextPath()));
        props.put("description", safeNonNull(tool.getDescription()));
        props.put("aiDescription", safeNonNull(tool.getAiDescription()));
        props.put("moduleId", tool.getModuleId());
        props.put("globalToolDefinitionId", tool.getGlobalToolDefinitionId());

        // 接口级 AI 摘要（如已生成，便于前端 tooltip 一次拉到位）
        Optional<SemanticDocEntity> aiDoc = semanticDocService.findByRef(
                SemanticDocEntity.LEVEL_SCAN_TOOL, tool.getProjectId(), null, tool.getId());
        aiDoc.ifPresent(doc -> props.put("semanticDocId", doc.getId()));

        node.setPropsJson(writeJsonOrNull(props));
        return repository.upsertNode(node);
    }

    /**
     * 递归 walk 参数树。FIELD_IN / FIELD_OUT 各自挂在 API 节点下，复合类型再挂一个 DTO 节点（项目内同名 DTO 复用同一节点）。
     */
    private void walkAndUpsertField(Long projectId,
                                    Long apiNodeId,
                                    Long parentNodeId,
                                    ToolDefinitionParameter parameter,
                                    String pathPrefix,
                                    String fieldKind,
                                    Map<String, ApiGraphNodeEntity> dtoNodeByType,
                                    Set<Long> keepIds) {
        if (parameter == null) {
            return;
        }
        String name = safeNonBlank(parameter.name(), "anonymous");
        if (isTransparentBodyWrapper(parameter)) {
            for (ToolDefinitionParameter child : parameter.children()) {
                walkAndUpsertField(projectId, apiNodeId, parentNodeId, child, pathPrefix, fieldKind,
                        dtoNodeByType, keepIds);
            }
            return;
        }
        String fieldPath = pathPrefix.isEmpty() ? name : pathPrefix + "." + name;

        ApiGraphNodeEntity fieldNode = new ApiGraphNodeEntity();
        fieldNode.setProjectId(projectId);
        fieldNode.setKind(fieldKind);
        fieldNode.setRefId(apiNodeId);
        fieldNode.setParentId(parentNodeId);
        fieldNode.setLabel(name);
        fieldNode.setTypeName(safeNonNull(parameter.type()));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("required", parameter.required());
        props.put("location", safeNonNull(parameter.location()));
        props.put("paramPath", fieldPath);
        props.put("description", safeNonNull(parameter.description()));
        fieldNode.setPropsJson(writeJsonOrNull(props));

        ApiGraphNodeEntity savedField = repository.upsertNode(fieldNode);
        keepIds.add(savedField.getId());

        // 复合类型：建 DTO 节点（同项目同 type 全局复用），并在字段 props 里记录 dtoNodeId
        String simpleTypeName = simpleTypeName(parameter.type());
        if (isCompositeType(simpleTypeName, parameter.children())) {
            ApiGraphNodeEntity dtoNode = dtoNodeByType.computeIfAbsent(simpleTypeName, key -> {
                ApiGraphNodeEntity dto = new ApiGraphNodeEntity();
                dto.setProjectId(projectId);
                dto.setKind(ApiGraphNodeKind.DTO);
                dto.setRefId(null);
                dto.setParentId(null);
                dto.setLabel(key);
                dto.setTypeName(safeNonNull(parameter.type()));
                dto.setPropsJson(writeJsonOrNull(Map.of("rawType", safeNonNull(parameter.type()))));
                return repository.upsertNode(dto);
            });
            keepIds.add(dtoNode.getId());

            // 字段 → DTO（OF_TYPE，归到 BELONGS_TO 边集合）
            ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
            edge.setProjectId(projectId);
            edge.setSourceNodeId(savedField.getId());
            edge.setTargetNodeId(dtoNode.getId());
            edge.setKind(ApiGraphEdgeKind.BELONGS_TO);
            edge.setSource(ApiGraphEdgeKind.SOURCE_AUTO);
            edge.setConfidence(1.0);
            edge.setEvidenceJson(writeJsonOrNull(Map.of("by", "of_type")));
            edge.setEnabled(Boolean.TRUE);
            repository.upsertEdge(edge);
        }

        if (parameter.children() != null && !parameter.children().isEmpty()) {
            for (ToolDefinitionParameter child : parameter.children()) {
                walkAndUpsertField(projectId, apiNodeId, savedField.getId(), child, fieldPath, fieldKind,
                        dtoNodeByType, keepIds);
            }
        }
    }

    /**
     * 当扫描结果只有类型名（如 Controller 的 responseType=WebApiResult&lt;TeamInfoVO&gt;）而没有 RESPONSE 子参 JSON 时，
     * {@link #walkAndUpsertField} 只会建一层复合出/入参节点，级联里选不到 VO 内字段。
     * 若项目中已有「同简单类型名」且已展开子树的字段，则把该子树克隆挂到当前裸复合字段下（paramPath 前缀替换）。
     */
    private void expandBareCompositeFields(Long projectId, Set<Long> keepIds) {
        List<ApiGraphNodeEntity> allNodes = repository.listNodesByProject(projectId);
        Map<Long, ApiGraphNodeEntity> byId = new HashMap<>(allNodes.size() * 2);
        for (ApiGraphNodeEntity n : allNodes) {
            byId.put(n.getId(), n);
        }
        List<ApiGraphEdgeEntity> edges = repository.listEdgesByProject(projectId);

        Map<Long, List<ApiGraphNodeEntity>> childrenByParent = new HashMap<>();
        for (ApiGraphNodeEntity n : allNodes) {
            if (n.getParentId() == null) {
                continue;
            }
            if (ApiGraphNodeKind.FIELD_IN.equals(n.getKind()) || ApiGraphNodeKind.FIELD_OUT.equals(n.getKind())) {
                childrenByParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
            }
        }

        Set<Long> fieldIdsWithDtoBelongs = new HashSet<>();
        for (ApiGraphEdgeEntity e : edges) {
            if (!Objects.equals(projectId, e.getProjectId())) {
                continue;
            }
            if (!ApiGraphEdgeKind.BELONGS_TO.equals(e.getKind())) {
                continue;
            }
            ApiGraphNodeEntity tgt = byId.get(e.getTargetNodeId());
            if (tgt != null && ApiGraphNodeKind.DTO.equals(tgt.getKind())) {
                fieldIdsWithDtoBelongs.add(e.getSourceNodeId());
            }
        }

        List<ApiGraphNodeEntity> fieldNodes = new ArrayList<>();
        for (ApiGraphNodeEntity n : allNodes) {
            if (ApiGraphNodeKind.FIELD_IN.equals(n.getKind()) || ApiGraphNodeKind.FIELD_OUT.equals(n.getKind())) {
                fieldNodes.add(n);
            }
        }

        // 构建 fieldId → BELONGS_TO DTO nodeId 映射
        Map<Long, Long> fieldToDtoId = new HashMap<>();
        for (ApiGraphEdgeEntity e : edges) {
            if (!Objects.equals(projectId, e.getProjectId())) continue;
            if (!ApiGraphEdgeKind.BELONGS_TO.equals(e.getKind())) continue;
            ApiGraphNodeEntity tgt = byId.get(e.getTargetNodeId());
            if (tgt != null && ApiGraphNodeKind.DTO.equals(tgt.getKind())) {
                fieldToDtoId.put(e.getSourceNodeId(), e.getTargetNodeId());
            }
        }

        for (ApiGraphNodeEntity bare : fieldNodes) {
            if (!fieldIdsWithDtoBelongs.contains(bare.getId())) {
                continue;
            }
            if (!childrenByParent.getOrDefault(bare.getId(), List.of()).isEmpty()) {
                continue;
            }
            String tpl = simpleTypeName(bare.getTypeName());
            if (tpl.isBlank() || isPrimitive(tpl)) {
                continue;
            }

            // 策略一：按同简单类型名找 witness（原有逻辑）
            Optional<ApiGraphNodeEntity> witnessOpt = pickWitnessFieldForTemplate(
                    fieldNodes, childrenByParent, fieldIdsWithDtoBelongs, tpl, bare.getId(), bare.getKind());

            // 策略二：若按类型名找不到，通过 BELONGS_TO DTO 节点找 ——
            //         只要另一个字段也引用了同一个 DTO 节点且有子级，就用它做模板
            if (witnessOpt.isEmpty()) {
                Long bareDtoId = fieldToDtoId.get(bare.getId());
                if (bareDtoId != null) {
                    witnessOpt = fieldNodes.stream()
                            .filter(w -> !Objects.equals(w.getId(), bare.getId()))
                            .filter(w -> Objects.equals(fieldToDtoId.get(w.getId()), bareDtoId))
                            .filter(w -> !childrenByParent.getOrDefault(w.getId(), List.of()).isEmpty())
                            .min(Comparator.comparingInt(w -> fieldPath(w).length()));
                }
            }

            if (witnessOpt.isEmpty()) {
                continue;
            }
            ApiGraphNodeEntity witness = witnessOpt.get();
            String wRoot = fieldPath(witness);
            String bRoot = fieldPath(bare);
            Long refId = bare.getRefId();
            if (refId == null) {
                continue;
            }
            cloneWitnessFieldSubtree(projectId, bare.getId(), witness, wRoot, bRoot, refId, bare.getKind(),
                    childrenByParent, edges, byId, keepIds);
        }
    }

    private Optional<ApiGraphNodeEntity> pickWitnessFieldForTemplate(
            List<ApiGraphNodeEntity> fieldNodes,
            Map<Long, List<ApiGraphNodeEntity>> childrenByParent,
            Set<Long> fieldIdsWithDtoBelongs,
            String templateSimple,
            Long excludeId,
            String preferredKind) {
        Optional<ApiGraphNodeEntity> sameKind = fieldNodes.stream()
                .filter(w -> preferredKind.equals(w.getKind()))
                .filter(w -> !Objects.equals(w.getId(), excludeId))
                .filter(w -> fieldIdsWithDtoBelongs.contains(w.getId()))
                .filter(w -> templateSimple.equals(simpleTypeName(w.getTypeName())))
                .filter(w -> !childrenByParent.getOrDefault(w.getId(), List.of()).isEmpty())
                .min(Comparator.comparingInt(w -> fieldPath(w).length()));
        if (sameKind.isPresent()) {
            return sameKind;
        }
        return fieldNodes.stream()
                .filter(w -> !Objects.equals(w.getId(), excludeId))
                .filter(w -> fieldIdsWithDtoBelongs.contains(w.getId()))
                .filter(w -> templateSimple.equals(simpleTypeName(w.getTypeName())))
                .filter(w -> !childrenByParent.getOrDefault(w.getId(), List.of()).isEmpty())
                .min(Comparator.comparingInt(w -> fieldPath(w).length()));
    }

    private void cloneWitnessFieldSubtree(Long projectId,
                                          Long bareRootId,
                                          ApiGraphNodeEntity witness,
                                          String witnessRootPath,
                                          String bareRootPath,
                                          Long targetRefId,
                                          String fieldKind,
                                          Map<Long, List<ApiGraphNodeEntity>> childrenByParent,
                                          List<ApiGraphEdgeEntity> edges,
                                          Map<Long, ApiGraphNodeEntity> nodeById,
                                          Set<Long> keepIds) {
        ArrayDeque<WitnessCloneFrame> queue = new ArrayDeque<>();
        for (ApiGraphNodeEntity ch : childrenByParent.getOrDefault(witness.getId(), List.of())) {
            queue.addLast(new WitnessCloneFrame(ch, bareRootId));
        }
        while (!queue.isEmpty()) {
            WitnessCloneFrame frame = queue.removeFirst();
            ApiGraphNodeEntity v = frame.node();
            Long newParentId = frame.parentId();

            String newParamPath = remapFieldPathPrefix(witnessRootPath, bareRootPath, fieldPath(v));
            ApiGraphNodeEntity clone = cloneFieldNode(projectId, v, targetRefId, newParentId, fieldKind, newParamPath);
            keepIds.add(clone.getId());

            copyBelongsToDtoEdges(projectId, v.getId(), clone.getId(), edges, nodeById);

            for (ApiGraphNodeEntity c : childrenByParent.getOrDefault(v.getId(), List.of())) {
                queue.addLast(new WitnessCloneFrame(c, clone.getId()));
            }
        }
    }

    private record WitnessCloneFrame(ApiGraphNodeEntity node, Long parentId) {
    }

    private static String remapFieldPathPrefix(String witnessRoot, String bareRoot, String pathV) {
        String suffix;
        if (witnessRoot.isEmpty()) {
            suffix = pathV;
        } else if (pathV.equals(witnessRoot)) {
            suffix = "";
        } else if (pathV.startsWith(witnessRoot + ".")) {
            suffix = pathV.substring(witnessRoot.length() + 1);
        } else {
            int dot = pathV.lastIndexOf('.');
            suffix = dot >= 0 ? pathV.substring(dot + 1) : pathV;
        }
        if (suffix.isEmpty()) {
            return bareRoot;
        }
        return bareRoot.isEmpty() ? suffix : bareRoot + "." + suffix;
    }

    private ApiGraphNodeEntity cloneFieldNode(Long projectId,
                                              ApiGraphNodeEntity template,
                                              Long refId,
                                              Long parentId,
                                              String kind,
                                              String newParamPath) {
        ApiGraphNodeEntity n = new ApiGraphNodeEntity();
        n.setProjectId(projectId);
        n.setKind(kind);
        n.setRefId(refId);
        n.setParentId(parentId);
        n.setLabel(template.getLabel());
        n.setTypeName(template.getTypeName());
        Map<String, Object> props = new LinkedHashMap<>(readJsonMap(template.getPropsJson()));
        props.put("paramPath", newParamPath);
        n.setPropsJson(writeJsonOrNull(props));
        return repository.upsertNode(n);
    }

    private void copyBelongsToDtoEdges(Long projectId,
                                       Long witnessFieldId,
                                       Long clonedFieldId,
                                       List<ApiGraphEdgeEntity> edges,
                                       Map<Long, ApiGraphNodeEntity> nodeById) {
        for (ApiGraphEdgeEntity e : edges) {
            if (!Objects.equals(projectId, e.getProjectId())) {
                continue;
            }
            if (!ApiGraphEdgeKind.BELONGS_TO.equals(e.getKind())) {
                continue;
            }
            if (!Objects.equals(witnessFieldId, e.getSourceNodeId())) {
                continue;
            }
            ApiGraphNodeEntity tgt = nodeById.get(e.getTargetNodeId());
            if (tgt == null || !ApiGraphNodeKind.DTO.equals(tgt.getKind())) {
                continue;
            }
            ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
            edge.setProjectId(projectId);
            edge.setSourceNodeId(clonedFieldId);
            edge.setTargetNodeId(e.getTargetNodeId());
            edge.setKind(ApiGraphEdgeKind.BELONGS_TO);
            edge.setSource(ApiGraphEdgeKind.SOURCE_AUTO);
            edge.setConfidence(1.0);
            edge.setEvidenceJson(writeJsonOrNull(Map.of("by", "cloned_from_witness")));
            edge.setEnabled(Boolean.TRUE);
            repository.upsertEdge(edge);
        }
    }

    // ====================================================================================
    // 二、自动推断「数据模型共享」紫色虚线边（MODEL_REF）
    // ====================================================================================

    /**
     * 按字段 type_name 聚合：所有引用同一非基础类型 DTO 的字段（跨 API）之间，建立紫色虚线
     * MODEL_REF 边（auto 来源；运营手动连线的 manual 边永不被覆盖）。
     * <p>
     * 实现策略：以 DTO 节点为 hub，所有引用方字段 ↔ DTO 节点之间已有 BELONGS_TO 边描述
     * "字段属于该 DTO 类型"；这里再按"同 DTO 的字段对"两两生成 MODEL_REF，方便前端直接画
     * 「字段-字段」紫线（图例三种边都聚焦在字段层）。规模上限：DTO 引用方为 N 时生成 N*(N-1)/2 条边。
     */
    @Transactional
    public int inferModelRefEdges(Long projectId) {
        repository.deleteAutoEdges(projectId, ApiGraphEdgeKind.MODEL_REF);

        List<ApiGraphNodeEntity> fieldsIn = repository.listNodesByProjectAndKind(projectId, ApiGraphNodeKind.FIELD_IN);
        List<ApiGraphNodeEntity> fieldsOut = repository.listNodesByProjectAndKind(projectId, ApiGraphNodeKind.FIELD_OUT);
        List<ApiGraphNodeEntity> all = new ArrayList<>();
        all.addAll(fieldsIn);
        all.addAll(fieldsOut);

        Map<String, List<ApiGraphNodeEntity>> bucketByType = new LinkedHashMap<>();
        for (ApiGraphNodeEntity field : all) {
            String simpleType = simpleTypeName(field.getTypeName());
            if (isPrimitive(simpleType)) {
                continue;
            }
            // 仅聚合"复合类型"，且 simpleType 必须像类名（首字母大写或 . 路径）
            if (!looksLikeCompositeName(simpleType)) {
                continue;
            }
            bucketByType.computeIfAbsent(simpleType, k -> new ArrayList<>()).add(field);
        }

        int generated = 0;
        for (Map.Entry<String, List<ApiGraphNodeEntity>> entry : bucketByType.entrySet()) {
            List<ApiGraphNodeEntity> fields = entry.getValue();
            if (fields.size() < 2) {
                continue;
            }
            for (int i = 0; i < fields.size(); i++) {
                for (int j = i + 1; j < fields.size(); j++) {
                    ApiGraphNodeEntity a = fields.get(i);
                    ApiGraphNodeEntity b = fields.get(j);
                    if (Objects.equals(a.getRefId(), b.getRefId())) {
                        // 同一 API 内部不画 MODEL_REF（噪音过大）
                        continue;
                    }
                    // 仅允许出参 ↔ 入参之间的 MODEL_REF，出参↔出参、入参↔入参不做连接
                    if (Objects.equals(a.getKind(), b.getKind())) {
                        continue;
                    }
                    ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
                    edge.setProjectId(projectId);
                    edge.setSourceNodeId(a.getId());
                    edge.setTargetNodeId(b.getId());
                    edge.setKind(ApiGraphEdgeKind.MODEL_REF);
                    edge.setSource(ApiGraphEdgeKind.SOURCE_AUTO);
                    edge.setConfidence(1.0);
                    edge.setStatus(ApiGraphEdgeKind.STATUS_CONFIRMED);
                    edge.setInferStrategy(ApiGraphEdgeKind.STRATEGY_DTO_MATCH);
                    edge.setEvidenceJson(writeJsonOrNull(Map.of("by", "shared_type", "type", entry.getKey())));
                    edge.setEnabled(Boolean.TRUE);
                    repository.upsertEdge(edge);
                    generated++;
                }
            }
        }
        return generated;
    }

    /**
     * Phase 4.1：自动推断「出参字段 → 入参字段」候选边。
     * <p>
     * 只生成 CANDIDATE 状态，不直接影响 Agent 调用；运营确认后才进入反哺链路。
     */
    @Transactional
    public int inferRequestResponseEdges(Long projectId) {
        List<ApiGraphNodeEntity> inputFields = repository.listNodesByProjectAndKind(projectId, ApiGraphNodeKind.FIELD_IN);
        List<ApiGraphNodeEntity> outputFields = repository.listNodesByProjectAndKind(projectId, ApiGraphNodeKind.FIELD_OUT);
        if (inputFields.isEmpty() || outputFields.isEmpty()) {
            return 0;
        }

        Set<String> rejectedIdentities = new LinkedHashSet<>();
        for (ApiGraphEdgeEntity edge : repository.listEdgesByProject(projectId)) {
            if (ApiGraphEdgeKind.STATUS_REJECTED.equalsIgnoreCase(edge.getStatus())) {
                rejectedIdentities.add(edgeIdentity(edge.getKind(), edge.getSourceNodeId(), edge.getTargetNodeId()));
            }
        }

        int generated = 0;
        for (ApiGraphNodeEntity source : outputFields) {
            for (ApiGraphNodeEntity target : inputFields) {
                if (Objects.equals(source.getRefId(), target.getRefId())) {
                    continue;
                }
                if (rejectedIdentities.contains(edgeIdentity(ApiGraphEdgeKind.REQUEST_REF, source.getId(), target.getId()))) {
                    continue;
                }
                double confidence = scoreFieldMatch(source, target);
                if (confidence < 0.65) {
                    continue;
                }
                ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
                edge.setProjectId(projectId);
                edge.setSourceNodeId(source.getId());
                edge.setTargetNodeId(target.getId());
                edge.setKind(ApiGraphEdgeKind.REQUEST_REF);
                edge.setSource(ApiGraphEdgeKind.SOURCE_AUTO);
                edge.setConfidence(roundConfidence(confidence));
                edge.setStatus(ApiGraphEdgeKind.STATUS_CANDIDATE);
                edge.setInferStrategy(ApiGraphEdgeKind.STRATEGY_SCHEMA_MATCH);
                edge.setEvidenceJson(writeJsonOrNull(Map.of(
                        "by", "field_schema_match",
                        "sourcePath", fieldPath(source),
                        "targetPath", fieldPath(target),
                        "sourceType", safeNonNull(source.getTypeName()),
                        "targetType", safeNonNull(target.getTypeName())
                )));
                edge.setEnabled(Boolean.TRUE);
                repository.upsertEdge(edge);
                generated++;
            }
        }
        return generated;
    }

    public List<ApiGraphEdgeEntity> listCandidates(Long projectId, String status, Double minConfidence) {
        String normalizedStatus = safeNonBlank(status, ApiGraphEdgeKind.STATUS_CANDIDATE).toUpperCase(Locale.ROOT);
        double min = minConfidence == null ? 0.0 : minConfidence;
        return repository.listEdgesByProject(projectId).stream()
                .filter(edge -> List.of(ApiGraphEdgeKind.REQUEST_REF, ApiGraphEdgeKind.RESPONSE_REF).contains(edge.getKind()))
                .filter(edge -> normalizedStatus.equalsIgnoreCase(statusOrConfirmed(edge)))
                .filter(edge -> edge.getConfidence() == null || edge.getConfidence() >= min)
                .sorted(Comparator.comparing(ApiGraphEdgeEntity::getConfidence,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ApiGraphEdgeEntity::getId))
                .toList();
    }

    public List<ParamSourceHint> buildParamSourceHints(Long projectId, String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }
        Optional<ToolDefinitionEntity> tool = toolDefinitionService.findByName(toolName);
        if (tool.isEmpty()) {
            return List.of();
        }
        List<ApiGraphNodeEntity> nodes = repository.listNodesByProject(projectId);
        Map<Long, ApiGraphNodeEntity> nodeById = nodes.stream()
                .collect(LinkedHashMap::new, (map, node) -> map.put(node.getId(), node), LinkedHashMap::putAll);
        Set<Long> apiNodeIds = new LinkedHashSet<>();
        for (ApiGraphNodeEntity node : nodes) {
            if (!ApiGraphNodeKind.API.equals(node.getKind())) {
                continue;
            }
            Object globalToolId = readJsonMap(node.getPropsJson()).get("globalToolDefinitionId");
            if (globalToolId != null && Objects.equals(String.valueOf(tool.get().getId()), String.valueOf(globalToolId))) {
                apiNodeIds.add(node.getId());
            }
        }
        if (apiNodeIds.isEmpty()) {
            return List.of();
        }
        return repository.listEdgesByProject(projectId).stream()
                .filter(edge -> ApiGraphEdgeKind.REQUEST_REF.equals(edge.getKind()))
                .filter(edge -> ApiGraphEdgeKind.STATUS_CONFIRMED.equalsIgnoreCase(statusOrConfirmed(edge)))
                .filter(edge -> {
                    ApiGraphNodeEntity target = nodeById.get(edge.getTargetNodeId());
                    return target != null && apiNodeIds.contains(target.getRefId());
                })
                .sorted(Comparator.comparing(ApiGraphEdgeEntity::getConfidence,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(edge -> toParamSourceHint(edge, nodeById))
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public ApiGraphEdgeEntity confirmCandidate(Long projectId, Long edgeId, String confirmedBy) {
        ApiGraphEdgeEntity edge = requireProjectEdge(projectId, edgeId);
        edge.setStatus(ApiGraphEdgeKind.STATUS_CONFIRMED);
        edge.setConfirmedBy(safeNonBlank(confirmedBy, "operator"));
        edge.setConfirmedAt(LocalDateTime.now());
        edge.setRejectReason(null);
        edge.setEnabled(Boolean.TRUE);
        ApiGraphEdgeEntity saved = repository.upsertEdge(edge);
        syncParamHintsToToolDescription(projectId, saved);
        return saved;
    }

    @Transactional
    public ApiGraphEdgeEntity rejectCandidate(Long projectId, Long edgeId, String rejectReason) {
        ApiGraphEdgeEntity edge = requireProjectEdge(projectId, edgeId);
        edge.setStatus(ApiGraphEdgeKind.STATUS_REJECTED);
        edge.setRejectReason(rejectReason);
        edge.setEnabled(Boolean.TRUE);
        return repository.upsertEdge(edge);
    }

    // ====================================================================================
    // 三、运营手动加/删边
    // ====================================================================================

    @Transactional
    public ApiGraphEdgeEntity upsertManualEdge(Long projectId, Long sourceNodeId, Long targetNodeId,
                                               String kind, String note) {
        if (sourceNodeId == null || targetNodeId == null) {
            throw new IllegalArgumentException("source / target 节点不能为空");
        }
        if (Objects.equals(sourceNodeId, targetNodeId)) {
            throw new IllegalArgumentException("不能连接自身");
        }
        String normalizedKind = normalizeKind(kind);
        ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
        edge.setProjectId(projectId);
        edge.setSourceNodeId(sourceNodeId);
        edge.setTargetNodeId(targetNodeId);
        edge.setKind(normalizedKind);
        edge.setSource(ApiGraphEdgeKind.SOURCE_MANUAL);
        edge.setConfidence(1.0);
        edge.setStatus(ApiGraphEdgeKind.STATUS_CONFIRMED);
        edge.setConfirmedAt(LocalDateTime.now());
        edge.setNote(note);
        edge.setEnabled(Boolean.TRUE);
        return repository.upsertEdge(edge);
    }

    @Transactional
    public boolean deleteEdge(Long edgeId) {
        if (edgeId == null) {
            return false;
        }
        return repository.deleteEdgeById(edgeId) > 0;
    }

    // ====================================================================================
    // 四、读取 / 布局 / 删除
    // ====================================================================================

    public ApiGraphSnapshot loadGraph(Long projectId) {
        List<ApiGraphNodeEntity> nodes = repository.listNodesByProject(projectId);
        List<ApiGraphEdgeEntity> edges = repository.listEdgesByProject(projectId);
        List<ApiGraphLayoutEntity> layouts = repository.listLayoutByProject(projectId);
        return new ApiGraphSnapshot(nodes, edges, layouts);
    }

    @Transactional
    public void saveLayout(Long projectId, List<LayoutPosition> positions) {
        if (positions == null) {
            return;
        }
        for (LayoutPosition position : positions) {
            if (position == null || position.nodeId() == null) {
                continue;
            }
            ApiGraphLayoutEntity entity = new ApiGraphLayoutEntity();
            entity.setProjectId(projectId);
            entity.setNodeId(position.nodeId());
            entity.setX(position.x() == null ? 0.0 : position.x());
            entity.setY(position.y() == null ? 0.0 : position.y());
            entity.setExtJson(position.extJson());
            repository.upsertLayout(entity);
        }
    }

    @Transactional
    public void deleteByProject(Long projectId) {
        if (projectId == null) {
            return;
        }
        repository.deleteByProject(projectId);
    }

    // ====================================================================================
    // 工具方法
    // ====================================================================================

    private static String normalizeKind(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of(ApiGraphEdgeKind.REQUEST_REF, ApiGraphEdgeKind.RESPONSE_REF,
                ApiGraphEdgeKind.MODEL_REF, ApiGraphEdgeKind.BELONGS_TO).contains(v)) {
            throw new IllegalArgumentException("未知的边类型: " + value);
        }
        return v;
    }

    private static boolean isResponseParameter(ToolDefinitionParameter p) {
        if (p == null) {
            return false;
        }
        String location = p.location();
        return location != null && location.equalsIgnoreCase("RESPONSE");
    }

    /** 常见 HTTP 响应包装类型（不区分大小写），剥壳后保留内部类型参数。 */
    private static final Set<String> RESPONSE_WRAPPER_NAMES = Set.of(
            "apiresult", "webapiresult", "apiresponse", "result",
            "responseentity", "response", "basresult", "commonresult",
            "restult", "ajaxresult", "jsonresult", "httpentity"
    );

    /**
     * OpenAPI / Controller 扫描器把 HTTP 响应类型写在 {@link ScanProjectToolEntity#getResponseType()}，
     * 一般不在 parametersJson 里带 location=RESPONSE 的根参数；若不补偿则图谱无 FIELD_OUT，
     * 前端「选择数据来源」级联永远为空。
     */
    private static ToolDefinitionParameter syntheticResponseFromTool(ScanProjectToolEntity tool) {
        if (tool == null) {
            return null;
        }
        String rt = tool.getResponseType();
        if (rt == null || rt.isBlank()) {
            return null;
        }
        String trimmed = rt.trim();
        String simple = simpleTypeName(trimmed);
        if (simple.isEmpty()) {
            return null;
        }
        if (isPrimitive(simple) && "void".equalsIgnoreCase(simple)) {
            return null;
        }
        // 剥掉常见泛型包装壳：WebApiResult<TeamInfoVO> → TeamInfoVO
        String unwrapped = unwrapResponseType(trimmed);
        return new ToolDefinitionParameter(
                "返回值",
                unwrapped,
                "由扫描结果的 responseType 生成，用于接口图谱出参与数据来源选择",
                false,
                "RESPONSE"
        );
    }

    /**
     * 若响应类型是常见包装泛型（如 WebApiResult&lt;T&gt;），提取内部类型参数；
     * 非包装类型原样返回。
     */
    private static String unwrapResponseType(String rawType) {
        if (rawType == null) {
            return rawType;
        }
        String trimmed = rawType.trim();
        int lt = trimmed.indexOf('<');
        int gt = trimmed.lastIndexOf('>');
        if (lt <= 0 || gt <= lt) {
            return trimmed;
        }
        String wrapperName = trimmed.substring(0, lt).trim().toLowerCase(Locale.ROOT);
        if (RESPONSE_WRAPPER_NAMES.contains(wrapperName)) {
            return trimmed.substring(lt + 1, gt).trim();
        }
        return trimmed;
    }

    /**
     * 扫描器为了表达 JSON 请求体根节点，会生成类似 body_json/json 的包装参数。
     * 它只是展示/承载 children 的技术根节点，不是业务 DTO/VO；图谱里应透明穿透，
     * 避免所有接口都连到一个无意义的 json DTO。
     */
    private static boolean isTransparentBodyWrapper(ToolDefinitionParameter p) {
        if (p == null || p.children() == null || p.children().isEmpty()) {
            return false;
        }
        String name = p.name() == null ? "" : p.name().trim().toLowerCase(Locale.ROOT);
        String type = simpleTypeName(p.type()).toLowerCase(Locale.ROOT);
        String location = p.location() == null ? "" : p.location().trim().toUpperCase(Locale.ROOT);
        boolean wrapperName = List.of("body_json", "bodyjson", "body", "request_body", "requestbody").contains(name);
        boolean wrapperType = List.of("json", "object", "map").contains(type);
        return wrapperName && wrapperType && ("BODY".equals(location) || location.isBlank());
    }

    private List<ToolDefinitionParameter> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, PARAM_LIST_TYPE);
        } catch (Exception ex) {
            log.warn("[ApiGraphService] failed to parse parameters json", ex);
            return List.of();
        }
    }

    private String writeJsonOrNull(Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            log.warn("[ApiGraphService] failed to write props json", ex);
            return null;
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            log.debug("[ApiGraphService] failed to parse props json: {}", json, ex);
            return Map.of();
        }
    }

    private ApiGraphEdgeEntity requireProjectEdge(Long projectId, Long edgeId) {
        ApiGraphEdgeEntity edge = repository.findEdgeById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("候选边不存在"));
        if (!Objects.equals(projectId, edge.getProjectId())) {
            throw new IllegalArgumentException("候选边不属于当前项目");
        }
        return edge;
    }

    private String statusOrConfirmed(ApiGraphEdgeEntity edge) {
        return safeNonBlank(edge.getStatus(), ApiGraphEdgeKind.STATUS_CONFIRMED);
    }

    private static String edgeIdentity(String kind, Long sourceNodeId, Long targetNodeId) {
        return kind + ":" + sourceNodeId + "->" + targetNodeId;
    }

    private double scoreFieldMatch(ApiGraphNodeEntity source, ApiGraphNodeEntity target) {
        double score = 0.0;
        String sourceName = normalizedFieldName(source);
        String targetName = normalizedFieldName(target);
        if (!sourceName.isBlank() && sourceName.equals(targetName)) {
            score += 0.35;
        } else if (isLikelyIdAlias(sourceName, targetName)) {
            score += 0.25;
        }
        if (isTypeCompatible(source.getTypeName(), target.getTypeName())) {
            score += 0.25;
        }
        String sourceSimple = simpleTypeName(source.getTypeName());
        String targetSimple = simpleTypeName(target.getTypeName());
        if (!sourceSimple.isBlank() && sourceSimple.equals(targetSimple) && looksLikeCompositeName(sourceSimple)) {
            score += 0.15;
        }
        if (fieldPath(source).equalsIgnoreCase(fieldPath(target)) && !fieldPath(source).isBlank()) {
            score += 0.1;
        }
        return Math.min(score, 0.95);
    }

    private String normalizedFieldName(ApiGraphNodeEntity node) {
        Object paramPath = readJsonMap(node.getPropsJson()).get("paramPath");
        String raw = safeNonBlank(paramPath == null ? null : String.valueOf(paramPath), node.getLabel());
        int dot = raw.lastIndexOf('.');
        if (dot >= 0 && dot < raw.length() - 1) {
            raw = raw.substring(dot + 1);
        }
        return raw.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isLikelyIdAlias(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if ("id".equals(a) && b.endsWith("id")) {
            return true;
        }
        if ("id".equals(b) && a.endsWith("id")) {
            return true;
        }
        return a.endsWith("id") && b.endsWith("id") && (a.contains(b.replace("id", "")) || b.contains(a.replace("id", "")));
    }

    private static boolean isTypeCompatible(String left, String right) {
        String l = simpleTypeName(left).toLowerCase(Locale.ROOT);
        String r = simpleTypeName(right).toLowerCase(Locale.ROOT);
        if (l.isBlank() || r.isBlank()) {
            return false;
        }
        if (l.equals(r)) {
            return true;
        }
        Set<String> numeric = Set.of("integer", "int", "long", "short", "byte", "number");
        return numeric.contains(l) && numeric.contains(r);
    }

    private String fieldPath(ApiGraphNodeEntity node) {
        Object value = readJsonMap(node.getPropsJson()).get("paramPath");
        return value == null ? "" : String.valueOf(value);
    }

    private static double roundConfidence(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private ParamSourceHint toParamSourceHint(ApiGraphEdgeEntity edge, Map<Long, ApiGraphNodeEntity> nodeById) {
        ApiGraphNodeEntity source = nodeById.get(edge.getSourceNodeId());
        ApiGraphNodeEntity target = nodeById.get(edge.getTargetNodeId());
        if (source == null || target == null) {
            return null;
        }
        ApiGraphNodeEntity sourceApi = nodeById.get(source.getRefId());
        ApiGraphNodeEntity targetApi = nodeById.get(target.getRefId());
        return new ParamSourceHint(
                fieldPath(target),
                target.getLabel(),
                targetApi == null ? "" : targetApi.getLabel(),
                fieldPath(source),
                source.getLabel(),
                sourceApi == null ? "" : sourceApi.getLabel(),
                edge.getConfidence()
        );
    }

    private void syncParamHintsToToolDescription(Long projectId, ApiGraphEdgeEntity edge) {
        try {
            List<ApiGraphNodeEntity> nodes = repository.listNodesByProject(projectId);
            Map<Long, ApiGraphNodeEntity> nodeById = nodes.stream()
                    .collect(LinkedHashMap::new, (map, node) -> map.put(node.getId(), node), LinkedHashMap::putAll);
            ApiGraphNodeEntity target = nodeById.get(edge.getTargetNodeId());
            if (target == null || !ApiGraphNodeKind.FIELD_IN.equals(target.getKind())) {
                return;
            }
            ApiGraphNodeEntity targetApi = nodeById.get(target.getRefId());
            if (targetApi == null) {
                return;
            }
            Object globalToolId = readJsonMap(targetApi.getPropsJson()).get("globalToolDefinitionId");
            if (globalToolId == null) {
                return;
            }
            Long id = Long.valueOf(String.valueOf(globalToolId));
            Optional<ToolDefinitionEntity> tool = toolDefinitionService.findById(id);
            if (tool.isEmpty()) {
                return;
            }
            List<ParamSourceHint> hints = buildParamSourceHints(projectId, tool.get().getName());
            if (hints.isEmpty()) {
                return;
            }
            String base = stripParamHintSection(safeNonBlank(tool.get().getAiDescription(), tool.get().getDescription()));
            String hintText = hints.stream()
                    .limit(5)
                    .map(hint -> "- " + hint.targetPath() + " 通常来自 " + hint.sourceApi() + " 的 " + hint.sourcePath() + "。")
                    .collect(java.util.stream.Collectors.joining("\n"));
            toolDefinitionService.setAiDescriptionAndReindex(id, base + "\n\n参数来源提示：\n" + hintText);
        } catch (Exception ex) {
            log.debug("[ApiGraphService] sync param hints to tool description skipped: {}", ex.toString());
        }
    }

    private String stripParamHintSection(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int idx = text.indexOf("\n\n参数来源提示：");
        if (idx < 0) {
            idx = text.indexOf("\n参数来源提示：");
        }
        return (idx < 0 ? text : text.substring(0, idx)).trim();
    }

    private static String safeNonNull(String s) {
        return s == null ? "" : s;
    }

    private static String safeNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        }
        return "";
    }

    /** "List&lt;UserDTO&gt;" → "UserDTO"；"com.x.UserDTO" → "UserDTO"；空串/基础类型保留原值。 */
    private static String simpleTypeName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        String t = rawType.trim();
        int lt = t.indexOf('<');
        int gt = t.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            t = t.substring(lt + 1, gt).trim();
        }
        // 数组：去掉末尾 []
        while (t.endsWith("[]")) {
            t = t.substring(0, t.length() - 2).trim();
        }
        int comma = t.indexOf(',');
        if (comma >= 0) {
            t = t.substring(0, comma).trim();
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        return t;
    }

    private static boolean isPrimitive(String simpleType) {
        if (simpleType == null || simpleType.isBlank()) {
            return true;
        }
        return PRIMITIVE_TYPE_NAMES.contains(simpleType.toLowerCase(Locale.ROOT));
    }

    /** 复合类型判定：非基础类型 OR 子字段非空。 */
    private static boolean isCompositeType(String simpleType, Collection<ToolDefinitionParameter> children) {
        if (isPrimitive(simpleType)) {
            return false;
        }
        if (children != null && !children.isEmpty()) {
            return true;
        }
        return looksLikeCompositeName(simpleType);
    }

    private static boolean looksLikeCompositeName(String simpleType) {
        if (simpleType == null || simpleType.isBlank()) {
            return false;
        }
        if (isPrimitive(simpleType)) {
            return false;
        }
        char first = simpleType.charAt(0);
        return Character.isUpperCase(first);
    }

    // ====================================================================================
    // 对外数据载体
    // ====================================================================================

    public record ApiGraphSnapshot(List<ApiGraphNodeEntity> nodes,
                                   List<ApiGraphEdgeEntity> edges,
                                   List<ApiGraphLayoutEntity> layouts) {
    }

    public record LayoutPosition(Long nodeId, Double x, Double y, String extJson) {
    }

    public record ParamSourceHint(String targetPath,
                                  String targetField,
                                  String targetApi,
                                  String sourcePath,
                                  String sourceField,
                                  String sourceApi,
                                  Double confidence) {
    }
}
