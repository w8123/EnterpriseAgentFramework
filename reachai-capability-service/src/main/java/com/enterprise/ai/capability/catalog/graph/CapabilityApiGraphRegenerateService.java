package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CapabilityApiGraphRegenerateService {

    private record NodeKey(Long projectId, String kind, Long refId, Long parentId, String label) {
        static NodeKey from(ApiGraphNodeEntity node) {
            return new NodeKey(node.getProjectId(), node.getKind(), node.getRefId(), node.getParentId(), node.getLabel());
        }
    }

    private record EdgeKey(Long projectId, String kind, Long sourceNodeId, Long targetNodeId, String source) {
        static EdgeKey from(ApiGraphEdgeEntity edge) {
            return new EdgeKey(edge.getProjectId(), edge.getKind(),
                    edge.getSourceNodeId(), edge.getTargetNodeId(), edge.getSource());
        }
    }

    private static final class ProjectionContext {
        private final Map<NodeKey, ApiGraphNodeEntity> nodes;
        private final Map<EdgeKey, ApiGraphEdgeEntity> edges;
        private final Set<Long> keepNodeIds = new LinkedHashSet<>();

        private ProjectionContext(Map<NodeKey, ApiGraphNodeEntity> nodes, Map<EdgeKey, ApiGraphEdgeEntity> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    private static final String MODULE = "MODULE";
    private static final String API = "API";
    private static final String DTO = "DTO";
    private static final String FIELD_IN = "FIELD_IN";
    private static final String FIELD_OUT = "FIELD_OUT";
    private static final String BELONGS_TO = "BELONGS_TO";
    private static final String SOURCE_AUTO = "auto";
    private static final TypeReference<List<ToolDefinitionParameter>> PARAM_LIST_TYPE = new TypeReference<>() {
    };

    private final ApiGraphNodeMapper nodeMapper;
    private final ApiGraphEdgeMapper edgeMapper;
    private final ApiGraphLayoutMapper layoutMapper;
    private final ScanModuleMapper moduleMapper;
    private final ScanProjectToolMapper toolMapper;
    private final SemanticDocMapper semanticDocMapper;
    private final CapabilityApiGraphOperationsService operationsService;
    private final CapabilityApiGraphSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CapabilityApiGraphSnapshotView regenerate(Long projectId) {
        edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>().eq(ApiGraphEdgeEntity::getProjectId, projectId));
        layoutMapper.delete(new LambdaQueryWrapper<ApiGraphLayoutEntity>().eq(ApiGraphLayoutEntity::getProjectId, projectId));
        nodeMapper.delete(new LambdaQueryWrapper<ApiGraphNodeEntity>().eq(ApiGraphNodeEntity::getProjectId, projectId));
        projectGraph(projectId, new ProjectionContext(new HashMap<>(), new HashMap<>()));
        operationsService.inferModelRefEdges(projectId);
        return snapshotService.loadSnapshot(projectId);
    }

    @Transactional
    public CapabilityApiGraphSnapshotView rebuild(Long projectId) {
        ProjectionContext context = loadProjectionContext(projectId);
        projectGraph(projectId, context);
        deleteNodesNotIn(projectId, context.keepNodeIds);
        operationsService.inferModelRefEdges(projectId);
        return snapshotService.loadSnapshot(projectId);
    }

    private ProjectionContext loadProjectionContext(Long projectId) {
        Map<NodeKey, ApiGraphNodeEntity> nodes = new HashMap<>();
        for (ApiGraphNodeEntity node : listExistingNodes(projectId)) {
            nodes.put(NodeKey.from(node), node);
        }
        Map<EdgeKey, ApiGraphEdgeEntity> edges = new HashMap<>();
        for (ApiGraphEdgeEntity edge : listExistingEdges(projectId)) {
            edges.put(EdgeKey.from(edge), edge);
        }
        return new ProjectionContext(nodes, edges);
    }

    private void projectGraph(Long projectId, ProjectionContext context) {
        Map<Long, Long> moduleNodeIds = new LinkedHashMap<>();
        for (ScanModuleEntity module : listModules(projectId)) {
            ApiGraphNodeEntity node = new ApiGraphNodeEntity();
            node.setProjectId(projectId);
            node.setKind(MODULE);
            node.setRefId(module.getId());
            node.setLabel(firstNonBlank(module.getDisplayName(), module.getName(), "module-" + module.getId()));
            node.setPropsJson(writeJsonOrNull(Map.of(
                    "name", nullToEmpty(module.getName()),
                    "displayName", nullToEmpty(module.getDisplayName()))));
            ApiGraphNodeEntity saved = saveNode(context, node);
            moduleNodeIds.put(module.getId(), saved.getId());
        }

        Map<String, ApiGraphNodeEntity> dtoNodeByType = new LinkedHashMap<>();
        for (ScanProjectToolEntity tool : listTools(projectId)) {
            ApiGraphNodeEntity apiNode = saveApiNode(context, projectId, tool, moduleNodeIds.get(tool.getModuleId()));
            ToolDefinitionParameter responseRoot = null;
            for (ToolDefinitionParameter parameter : parseParameters(tool.getParametersJson())) {
                if (isResponseParameter(parameter)) {
                    responseRoot = parameter;
                } else {
                    walkField(context, projectId, apiNode.getId(), apiNode.getId(),
                            parameter, "", FIELD_IN, dtoNodeByType);
                }
            }
            if (responseRoot == null) {
                responseRoot = syntheticResponseFromTool(tool);
            }
            if (responseRoot == null) {
                continue;
            }
            if (responseRoot.children() == null || responseRoot.children().isEmpty()) {
                walkField(context, projectId, apiNode.getId(), apiNode.getId(),
                        responseRoot, "", FIELD_OUT, dtoNodeByType);
            } else {
                for (ToolDefinitionParameter child : responseRoot.children()) {
                    walkField(context, projectId, apiNode.getId(), apiNode.getId(),
                            child, "", FIELD_OUT, dtoNodeByType);
                }
            }
        }
    }

    private ApiGraphNodeEntity saveApiNode(
            ProjectionContext context,
            Long projectId,
            ScanProjectToolEntity tool,
            Long moduleNodeId) {
        ApiGraphNodeEntity node = new ApiGraphNodeEntity();
        node.setProjectId(projectId);
        node.setKind(API);
        node.setRefId(tool.getId());
        node.setParentId(moduleNodeId);
        node.setLabel(firstNonBlank(tool.getName(), "api-" + tool.getId()));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scanToolId", tool.getId());
        props.put("httpMethod", nullToEmpty(tool.getHttpMethod()));
        props.put("endpointPath", nullToEmpty(tool.getEndpointPath()));
        props.put("contextPath", nullToEmpty(tool.getContextPath()));
        props.put("description", nullToEmpty(tool.getDescription()));
        props.put("aiDescription", nullToEmpty(tool.getAiDescription()));
        props.put("moduleId", tool.getModuleId());
        props.put("globalToolDefinitionId", tool.getGlobalToolDefinitionId());
        findSemanticDoc(tool).ifPresent(doc -> props.put("semanticDocId", doc.getId()));
        node.setPropsJson(writeJsonOrNull(props));
        return saveNode(context, node);
    }

    private void walkField(ProjectionContext context,
                           Long projectId,
                           Long apiNodeId,
                           Long parentNodeId,
                           ToolDefinitionParameter parameter,
                           String pathPrefix,
                           String fieldKind,
                           Map<String, ApiGraphNodeEntity> dtoNodeByType) {
        if (parameter == null) {
            return;
        }
        if (isTransparentBodyWrapper(parameter)) {
            for (ToolDefinitionParameter child : parameter.children()) {
                walkField(context, projectId, apiNodeId, parentNodeId, child, pathPrefix, fieldKind, dtoNodeByType);
            }
            return;
        }
        String name = firstNonBlank(parameter.name(), "anonymous");
        String fieldPath = pathPrefix.isEmpty() ? name : pathPrefix + "." + name;
        ApiGraphNodeEntity field = new ApiGraphNodeEntity();
        field.setProjectId(projectId);
        field.setKind(fieldKind);
        field.setRefId(apiNodeId);
        field.setParentId(parentNodeId);
        field.setLabel(name);
        field.setTypeName(nullToEmpty(parameter.type()));
        field.setPropsJson(writeJsonOrNull(Map.of(
                "required", parameter.required(),
                "location", nullToEmpty(parameter.location()),
                "paramPath", fieldPath,
                "description", nullToEmpty(parameter.description()))));
        ApiGraphNodeEntity savedField = saveNode(context, field);

        String simpleType = simpleTypeName(parameter.type());
        if (isCompositeType(simpleType, parameter.children())) {
            ApiGraphNodeEntity dto = dtoNodeByType.computeIfAbsent(
                    simpleType,
                    type -> saveDtoNode(context, projectId, type, parameter.type()));
            saveBelongsTo(context, projectId, savedField.getId(), dto.getId());
        }
        for (ToolDefinitionParameter child : parameter.children()) {
            walkField(context, projectId, apiNodeId, savedField.getId(), child, fieldPath, fieldKind, dtoNodeByType);
        }
    }

    private ApiGraphNodeEntity saveDtoNode(ProjectionContext context, Long projectId, String label, String rawType) {
        ApiGraphNodeEntity dto = new ApiGraphNodeEntity();
        dto.setProjectId(projectId);
        dto.setKind(DTO);
        dto.setLabel(label);
        dto.setTypeName(nullToEmpty(rawType));
        dto.setPropsJson(writeJsonOrNull(Map.of("rawType", nullToEmpty(rawType))));
        return saveNode(context, dto);
    }

    private ApiGraphNodeEntity saveNode(ProjectionContext context, ApiGraphNodeEntity node) {
        NodeKey key = NodeKey.from(node);
        ApiGraphNodeEntity existing = context.nodes.get(key);
        if (existing != null) {
            boolean changed = !Objects.equals(existing.getTypeName(), node.getTypeName()) ||
                    !Objects.equals(existing.getPropsJson(), node.getPropsJson());
            if (changed) {
                existing.setTypeName(node.getTypeName());
                existing.setPropsJson(node.getPropsJson());
                nodeMapper.updateById(existing);
            }
            context.keepNodeIds.add(existing.getId());
            return existing;
        }
        nodeMapper.insert(node);
        context.nodes.put(key, node);
        context.keepNodeIds.add(node.getId());
        return node;
    }

    private void saveBelongsTo(ProjectionContext context, Long projectId, Long sourceNodeId, Long targetNodeId) {
        ApiGraphEdgeEntity edge = new ApiGraphEdgeEntity();
        edge.setProjectId(projectId);
        edge.setSourceNodeId(sourceNodeId);
        edge.setTargetNodeId(targetNodeId);
        edge.setKind(BELONGS_TO);
        edge.setSource(SOURCE_AUTO);
        edge.setConfidence(1.0);
        edge.setEvidenceJson(writeJsonOrNull(Map.of("by", "of_type")));
        edge.setEnabled(Boolean.TRUE);
        saveEdge(context, edge);
    }

    private void saveEdge(ProjectionContext context, ApiGraphEdgeEntity edge) {
        EdgeKey key = EdgeKey.from(edge);
        ApiGraphEdgeEntity existing = context.edges.get(key);
        if (existing != null) {
            existing.setConfidence(edge.getConfidence());
            existing.setEvidenceJson(edge.getEvidenceJson());
            existing.setEnabled(Boolean.TRUE);
            edgeMapper.updateById(existing);
            return;
        }
        edgeMapper.insert(edge);
        context.edges.put(key, edge);
    }

    private void deleteNodesNotIn(Long projectId, Set<Long> keepIds) {
        List<ApiGraphNodeEntity> orphans = listExistingNodes(projectId).stream()
                .filter(node -> !keepIds.contains(node.getId()))
                .toList();
        if (orphans.isEmpty()) {
            return;
        }
        List<Long> orphanIds = orphans.stream().map(ApiGraphNodeEntity::getId).toList();
        edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .in(ApiGraphEdgeEntity::getSourceNodeId, orphanIds)
                .or()
                .in(ApiGraphEdgeEntity::getTargetNodeId, orphanIds));
        LambdaQueryWrapper<ApiGraphNodeEntity> delete = new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId);
        if (!keepIds.isEmpty()) {
            delete.notIn(ApiGraphNodeEntity::getId, keepIds);
        }
        nodeMapper.delete(delete);
    }

    private List<ApiGraphNodeEntity> listExistingNodes(Long projectId) {
        List<ApiGraphNodeEntity> nodes = nodeMapper.selectList(new LambdaQueryWrapper<ApiGraphNodeEntity>()
                .eq(ApiGraphNodeEntity::getProjectId, projectId)
                .orderByAsc(ApiGraphNodeEntity::getId));
        return nodes == null ? List.of() : nodes;
    }

    private List<ApiGraphEdgeEntity> listExistingEdges(Long projectId) {
        List<ApiGraphEdgeEntity> edges = edgeMapper.selectList(new LambdaQueryWrapper<ApiGraphEdgeEntity>()
                .eq(ApiGraphEdgeEntity::getProjectId, projectId)
                .orderByAsc(ApiGraphEdgeEntity::getId));
        return edges == null ? List.of() : edges;
    }

    private List<ScanModuleEntity> listModules(Long projectId) {
        List<ScanModuleEntity> modules = moduleMapper.selectList(new LambdaQueryWrapper<ScanModuleEntity>()
                .eq(ScanModuleEntity::getProjectId, projectId)
                .orderByAsc(ScanModuleEntity::getName));
        return modules == null ? List.of() : modules;
    }

    private List<ScanProjectToolEntity> listTools(Long projectId) {
        List<ScanProjectToolEntity> tools = toolMapper.selectList(new LambdaQueryWrapper<ScanProjectToolEntity>()
                .eq(ScanProjectToolEntity::getProjectId, projectId)
                .orderByAsc(ScanProjectToolEntity::getId));
        return tools == null ? List.of() : tools;
    }

    private java.util.Optional<SemanticDocEntity> findSemanticDoc(ScanProjectToolEntity tool) {
        return java.util.Optional.ofNullable(semanticDocMapper.selectOne(new LambdaQueryWrapper<SemanticDocEntity>()
                .eq(SemanticDocEntity::getLevel, SemanticDocEntity.LEVEL_SCAN_TOOL)
                .eq(SemanticDocEntity::getProjectId, tool.getProjectId())
                .isNull(SemanticDocEntity::getModuleId)
                .eq(SemanticDocEntity::getToolId, tool.getId())
                .last("limit 1")));
    }

    private List<ToolDefinitionParameter> parseParameters(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, PARAM_LIST_TYPE);
        } catch (Exception ex) {
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
            return null;
        }
    }

    private static ToolDefinitionParameter syntheticResponseFromTool(ScanProjectToolEntity tool) {
        if (tool == null || tool.getResponseType() == null || tool.getResponseType().isBlank()) {
            return null;
        }
        String type = unwrapResponseType(tool.getResponseType().trim());
        if ("void".equalsIgnoreCase(simpleTypeName(type))) {
            return null;
        }
        return new ToolDefinitionParameter(
                "返回值",
                type,
                "由扫描结果的 responseType 生成，用于接口图谱出参与数据来源选择",
                false,
                "RESPONSE");
    }

    private static String unwrapResponseType(String rawType) {
        int lt = rawType.indexOf('<');
        int gt = rawType.lastIndexOf('>');
        if (lt <= 0 || gt <= lt) {
            return rawType;
        }
        String wrapper = rawType.substring(0, lt).trim().toLowerCase(java.util.Locale.ROOT);
        if (List.of("apiresult", "webapiresult", "apiresponse", "result", "responseentity",
                "response", "basresult", "commonresult", "restult", "ajaxresult", "jsonresult",
                "httpentity").contains(wrapper)) {
            return rawType.substring(lt + 1, gt).trim();
        }
        return rawType;
    }

    private static boolean isResponseParameter(ToolDefinitionParameter parameter) {
        return parameter != null && parameter.location() != null && parameter.location().equalsIgnoreCase("RESPONSE");
    }

    private static boolean isTransparentBodyWrapper(ToolDefinitionParameter parameter) {
        if (parameter == null || parameter.children() == null || parameter.children().isEmpty()) {
            return false;
        }
        String name = parameter.name() == null ? "" : parameter.name().trim().toLowerCase(java.util.Locale.ROOT);
        String type = simpleTypeName(parameter.type()).toLowerCase(java.util.Locale.ROOT);
        String location = parameter.location() == null ? "" : parameter.location().trim().toUpperCase(java.util.Locale.ROOT);
        return List.of("body_json", "bodyjson", "body", "request_body", "requestbody").contains(name) &&
                List.of("json", "object", "map").contains(type) &&
                ("BODY".equals(location) || location.isBlank());
    }

    private static boolean isCompositeType(String simpleType, List<ToolDefinitionParameter> children) {
        return !isPrimitive(simpleType) && ((children != null && !children.isEmpty()) || looksLikeCompositeName(simpleType));
    }

    private static String simpleTypeName(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "";
        }
        String type = rawType.trim();
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            type = type.substring(lt + 1, gt).trim();
        }
        while (type.endsWith("[]")) {
            type = type.substring(0, type.length() - 2).trim();
        }
        int comma = type.indexOf(',');
        if (comma >= 0) {
            type = type.substring(0, comma).trim();
        }
        int dot = type.lastIndexOf('.');
        if (dot >= 0) {
            type = type.substring(dot + 1);
        }
        return type;
    }

    private static boolean isPrimitive(String simpleType) {
        if (simpleType == null || simpleType.isBlank()) {
            return true;
        }
        return java.util.Set.of("string", "integer", "int", "long", "short", "byte", "float",
                "double", "decimal", "number", "boolean", "bool", "date", "datetime", "time",
                "object", "json", "map", "any", "void", "null", "char")
                .contains(simpleType.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean looksLikeCompositeName(String simpleType) {
        return simpleType != null && !simpleType.isBlank() && !isPrimitive(simpleType) &&
                Character.isUpperCase(simpleType.charAt(0));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
