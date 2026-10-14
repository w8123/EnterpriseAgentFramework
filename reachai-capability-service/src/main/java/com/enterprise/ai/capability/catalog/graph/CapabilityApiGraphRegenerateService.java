package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphEdgeMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphLayoutMapper;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticDocumentStore;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubjectResolver;
import com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService;
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

    private record NodeKey(String nodeKey) {
        static NodeKey from(ApiGraphNodeEntity node) {
            return new NodeKey(node.getNodeKey());
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

    private final ApiGraphNodeMapper nodeMapper;
    private final ApiGraphEdgeMapper edgeMapper;
    private final ApiGraphLayoutMapper layoutMapper;
    private final CatalogSubjectResolver subjects;
    private final CapabilityCatalogGroupingService grouping;
    private final CapabilitySemanticDocumentStore semanticDocuments;
    private final ScanProjectMapper projects;
    private final CapabilityApiGraphOperationsService operationsService;
    private final CapabilityApiGraphSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CapabilityApiGraphSnapshotView regenerate(Long projectId) {
        subjects.requireProject(projectId);
        projects.lockCapabilityChanges(projectId);
        edgeMapper.delete(new LambdaQueryWrapper<ApiGraphEdgeEntity>().eq(ApiGraphEdgeEntity::getProjectId, projectId));
        layoutMapper.delete(new LambdaQueryWrapper<ApiGraphLayoutEntity>().eq(ApiGraphLayoutEntity::getProjectId, projectId));
        nodeMapper.delete(new LambdaQueryWrapper<ApiGraphNodeEntity>().eq(ApiGraphNodeEntity::getProjectId, projectId));
        projectGraph(projectId, new ProjectionContext(new HashMap<>(), new HashMap<>()));
        operationsService.inferModelRefEdges(projectId);
        return snapshotService.loadSnapshot(projectId);
    }

    @Transactional
    public CapabilityApiGraphSnapshotView rebuild(Long projectId) {
        subjects.requireProject(projectId);
        projects.lockCapabilityChanges(projectId);
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
        for (ScanModuleEntity module : grouping.list(projectId)) {
            ApiGraphNodeEntity node = new ApiGraphNodeEntity();
            node.setProjectId(projectId);
            node.setKind(MODULE);
            node.setNodeKey(CatalogSubjectResolver.digest("group:" + module.getId()));
            node.setRefId(module.getId());
            node.setLabel(firstNonBlank(module.getDisplayName(), module.getName(), "module-" + module.getId()));
            node.setPropsJson(writeJsonOrNull(Map.of(
                    "name", nullToEmpty(module.getName()),
                    "displayName", nullToEmpty(module.getDisplayName()))));
            ApiGraphNodeEntity saved = saveNode(context, node);
            moduleNodeIds.put(module.getId(), saved.getId());
        }

        Map<String, ApiGraphNodeEntity> dtoNodeByType = new LinkedHashMap<>();
        for (CatalogSubject subject : subjects.operations(projectId)) {
            Long groupId = grouping.groups(subject).stream().map(ScanModuleEntity::getId).findFirst().orElse(null);
            ApiGraphNodeEntity apiNode = saveApiNode(context, projectId, subject, moduleNodeIds.get(groupId));
            for (ToolDefinitionParameter parameter : subject.parameters()) {
                if (isResponseParameter(parameter)) {
                    // Keep distinct HTTP response schemas and all declared method return roots.
                    walkField(context, projectId, apiNode.getId(), apiNode.getId(),
                            parameter, "", FIELD_OUT, dtoNodeByType, subject);
                } else {
                    walkField(context, projectId, apiNode.getId(), apiNode.getId(),
                            parameter, "", FIELD_IN, dtoNodeByType, subject);
                }
            }
        }
    }

    private ApiGraphNodeEntity saveApiNode(
            ProjectionContext context,
            Long projectId,
            CatalogSubject subject,
            Long moduleNodeId) {
        ApiGraphNodeEntity node = new ApiGraphNodeEntity();
        node.setProjectId(projectId);
        node.setKind(API);
        node.setRefId(subject.subjectId());
        node.setNodeKey(CatalogSubjectResolver.digest("operation:" + subject.subjectType() + ":" + subject.subjectKey()));
        adoptCandidateNode(context, node, subject, null);
        node.setParentId(moduleNodeId);
        node.setLabel(firstNonBlank(subject.title(), subject.subjectKey()));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("assetType", subject.subjectType()); props.put("assetId", subject.subjectId());
        props.put("subjectType", subject.subjectType()); props.put("subjectId", subject.subjectId());
        props.put("qualifiedName", subject.subjectKey()); props.put("name", subject.subjectKey());
        props.put("title", nullToEmpty(subject.title())); props.put("contractHash", nullToEmpty(subject.contractHash()));
        props.put("accepted", subject.accepted()); props.put("httpMethod", nullToEmpty(subject.httpMethod()));
        props.put("endpointPath", nullToEmpty(subject.endpointPath())); props.put("contextPath", "");
        props.put("description", nullToEmpty(subject.description()));
        props.put("sourceObservationId", subject.observations().isEmpty() ? null : subject.observations().get(0).getId());
        props.put("sourceObservationIds", subject.observations().stream().map(ScanProjectToolEntity::getId).toList());
        findSemanticDoc(subject).ifPresent(doc -> { props.put("semanticDocId", doc.getId()); props.put("aiDescription", doc.getContentMd()); });
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
                           Map<String, ApiGraphNodeEntity> dtoNodeByType,
                           CatalogSubject subject) {
        if (parameter == null) {
            return;
        }
        if (isTransparentBodyWrapper(parameter)) {
            for (ToolDefinitionParameter child : parameter.children()) {
                walkField(context, projectId, apiNodeId, parentNodeId, child, pathPrefix, fieldKind, dtoNodeByType, subject);
            }
            return;
        }
        String name = firstNonBlank(parameter.name(), "anonymous");
        String fieldPath = pathPrefix.isEmpty() ? name : pathPrefix + "." + name;
        ApiGraphNodeEntity field = new ApiGraphNodeEntity();
        field.setProjectId(projectId);
        field.setKind(fieldKind);
        field.setNodeKey(CatalogSubjectResolver.digest("field:" + subject.subjectType() + ":" + subject.subjectKey()
                + ":" + fieldKind + ":" + nullToEmpty(parameter.location()) + ":" + fieldPath));
        adoptCandidateNode(context, field, subject, fieldKind + ":" + nullToEmpty(parameter.location()) + ":" + fieldPath);
        field.setRefId(apiNodeId);
        field.setParentId(parentNodeId);
        field.setLabel(name);
        field.setTypeName(nullToEmpty(parameter.type()));
        Map<String, Object> fieldProps = new LinkedHashMap<>(Map.of(
                "required", parameter.required(),
                "location", nullToEmpty(parameter.location()),
                "paramPath", fieldPath,
                "description", nullToEmpty(parameter.description()),
                "contractHash", nullToEmpty(subject.contractHash()), "accepted", subject.accepted(),
                "subjectType", subject.subjectType(), "subjectId", subject.subjectId()));
        if (parameter.metadata() instanceof Map<?, ?> metadata) fieldProps.put("schemaHash", metadata.getOrDefault("schemaHash", null));
        fieldProps.put("composite", isCompositeType(simpleTypeName(parameter.type()), parameter.children()));
        field.setPropsJson(writeJsonOrNull(fieldProps));
        ApiGraphNodeEntity savedField = saveNode(context, field);

        String simpleType = simpleTypeName(parameter.type());
        if (isCompositeType(simpleType, parameter.children())) {
            String identity = nullToEmpty(parameter.type()) + ":" + writeJsonOrNull(Map.of("metadata", parameter.metadata() == null ? "" : parameter.metadata()));
            ApiGraphNodeEntity dto = dtoNodeByType.computeIfAbsent(identity,
                    type -> saveDtoNode(context, projectId, isPrimitive(simpleType) ? name + " schema" : simpleType, parameter.type(), identity));
            saveBelongsTo(context, projectId, savedField.getId(), dto.getId());
        }
        for (ToolDefinitionParameter child : parameter.children()) {
            walkField(context, projectId, apiNodeId, savedField.getId(), child, fieldPath, fieldKind, dtoNodeByType, subject);
        }
    }

    private ApiGraphNodeEntity saveDtoNode(ProjectionContext context, Long projectId, String label, String rawType, String identity) {
        ApiGraphNodeEntity dto = new ApiGraphNodeEntity();
        dto.setProjectId(projectId);
        dto.setKind(DTO);
        dto.setNodeKey(CatalogSubjectResolver.digest("dto:" + identity));
        dto.setLabel(label);
        dto.setTypeName(nullToEmpty(rawType));
        dto.setPropsJson(writeJsonOrNull(Map.of("rawType", nullToEmpty(rawType))));
        return saveNode(context, dto);
    }

    private ApiGraphNodeEntity saveNode(ProjectionContext context, ApiGraphNodeEntity node) {
        node.setActive(Boolean.TRUE);
        NodeKey key = NodeKey.from(node);
        ApiGraphNodeEntity existing = context.nodes.get(key);
        if (existing != null) {
            boolean changed = !Objects.equals(existing.getTypeName(), node.getTypeName()) ||
                    !Objects.equals(existing.getPropsJson(), node.getPropsJson()) || !Objects.equals(existing.getLabel(), node.getLabel())
                    || !Objects.equals(existing.getParentId(), node.getParentId()) || !Boolean.TRUE.equals(existing.getActive());
            if (changed) {
                existing.setTypeName(node.getTypeName());
                existing.setPropsJson(node.getPropsJson());
                existing.setLabel(node.getLabel()); existing.setParentId(node.getParentId()); existing.setActive(Boolean.TRUE);
                existing.setNodeKey(node.getNodeKey()); existing.setRefId(node.getRefId());
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

    private void adoptCandidateNode(ProjectionContext context, ApiGraphNodeEntity target, CatalogSubject subject, String fieldPath) {
        NodeKey targetKey = NodeKey.from(target);
        if (context.nodes.containsKey(targetKey) || CatalogSubject.SOURCE_OBSERVATION.equals(subject.subjectType())) return;
        for (ScanProjectToolEntity observation : subject.observations()) {
            String oldIdentity = fieldPath == null ? "operation:SOURCE_OBSERVATION:source-observation:" + observation.getId()
                    : "field:SOURCE_OBSERVATION:source-observation:" + observation.getId() + ":" + fieldPath;
            ApiGraphNodeEntity candidate = context.nodes.remove(new NodeKey(CatalogSubjectResolver.digest(oldIdentity)));
            if (candidate != null) { context.nodes.put(targetKey, candidate); return; }
        }
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
        for (ApiGraphEdgeEntity edge : listExistingEdges(projectId)) {
            if (orphanIds.contains(edge.getSourceNodeId()) || orphanIds.contains(edge.getTargetNodeId())) {
                edge.setEnabled(Boolean.FALSE); edge.setRejectReason("所属字段或分组已变化，关系保留为失效证据"); edgeMapper.updateById(edge);
            }
        }
        for (ApiGraphNodeEntity orphan : orphans) { orphan.setActive(Boolean.FALSE); nodeMapper.updateById(orphan); }
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

    private java.util.Optional<SemanticDocEntity> findSemanticDoc(CatalogSubject subject) {
        return semanticDocuments.findInLockedProject(subject);
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
        return (children != null && !children.isEmpty()) || (!isPrimitive(simpleType) && looksLikeCompositeName(simpleType));
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
